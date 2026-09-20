package com.guyu.agentteam.service.tool;

import com.guyu.agentteam.common.Images;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * view_image 工具：把沙箱内的图片文件重新注入为视觉块，供模型按需重看。
 * 场景：历史图片被上下文压缩降级为文字注记（已阅）后，用户追问图片细节时模型可调用本工具重看。
 */
@Service
public class ViewImageTools {

    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;

    private final WorkspaceFileTools fileTools;

    public ViewImageTools(WorkspaceFileTools fileTools) {
        this.fileTools = fileTools;
    }

    /** 沙箱跟随给定会话（编排协作传协作目标会话，建群后自动切换） */
    public void register(Toolkit toolkit, Supplier<String> conversationId) {
        toolkit.registerTool(new ViewImageTool(fileTools, conversationId));
    }

    public static class ViewImageTool {

        private final WorkspaceFileTools fileTools;
        private final Supplier<String> conversationId;

        ViewImageTool(WorkspaceFileTools fileTools, Supplier<String> conversationId) {
            this.fileTools = fileTools;
            this.conversationId = conversationId;
        }

        @Tool(description = "Load an image file (png/jpg/jpeg/gif/webp/bmp, max 8MB) from the sandbox into the "
                + "conversation so you can actually see it. Use it to re-examine a previously-viewed image "
                + "(marked 已阅 in the history) when the user asks about its details, or to inspect any image file.")
        public ToolResultBlock viewImage(
                @ToolParam(name = "path", required = true,
                        description = "Image path: relative to the workspace root, or an absolute path inside the allowed sandbox")
                String path) {
            if (path == null || path.isBlank()) {
                return ToolResultBlock.error("path 不能为空");
            }
            Path target = resolve(path.trim());
            if (target == null) {
                String roots = fileTools.allowedRootsFor(conversationId.get()).stream()
                        .map(Path::toString).collect(Collectors.joining("、"));
                return ToolResultBlock.error("路径不在允许范围内：" + path + "。允许的根目录：" + roots);
            }
            try {
                if (!Files.isRegularFile(target)) {
                    return ToolResultBlock.error("图片文件不存在：" + target);
                }
                if (Files.size(target) > MAX_IMAGE_BYTES) {
                    return ToolResultBlock.error("图片超过 8MB 上限，无法加载：" + target);
                }
                String mime = Images.mediaType(target.getFileName().toString());
                if (mime == null) {
                    return ToolResultBlock.error("不是支持的图片格式（png/jpg/jpeg/gif/webp/bmp）：" + target);
                }
                ImageBlock img = ImageBlock.builder()
                        .source(Base64Source.builder()
                                .mediaType(mime)
                                .data(Base64.getEncoder().encodeToString(Files.readAllBytes(target)))
                                .build())
                        .build();
                String forward = target.toString().replace('\\', '/');
                return ToolResultBlock.of(List.of(
                        TextBlock.builder().text("已加载图片：" + forward + "，请直接描述或分析你看到的内容。").build(),
                        img));
            } catch (IOException e) {
                return ToolResultBlock.error("读取图片失败：" + e.getMessage());
            }
        }

        /** 相对路径解析到主工作区；绝对路径必须落在允许范围内，越界返回 null */
        private Path resolve(String raw) {
            String t = raw;
            // 兼容模型回传的 /C:/... 形态
            if (t.startsWith("/") && t.length() >= 2 && t.charAt(1) == ':') {
                t = t.substring(1);
            }
            Path p;
            try {
                p = Paths.get(t);
            } catch (Exception e) {
                return null;
            }
            Path abs = (p.isAbsolute() ? p : Paths.get(fileTools.primaryRoot()).resolve(p)).normalize();
            for (Path root : fileTools.allowedRootsFor(conversationId.get())) {
                if (abs.startsWith(root)) {
                    return abs;
                }
            }
            return null;
        }
    }
}
