package com.guyu.agentteam.service.tool;

import com.guyu.agentteam.entity.Agent;
import com.guyu.agentteam.entity.ModelPreset;
import com.guyu.agentteam.repository.ModelPresetRepository;
import com.guyu.agentteam.service.ModelPresetService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * generate_image 工具：智能体绑定图像预设后注册，调用外部文生图服务并把图片
 * 落到主工作区 generated/ 目录（文件名时间戳+随机、CREATE_NEW 只增不覆盖）。
 * 生图是用户主动要求的创作行为且写入位置受控，豁免审批卡片。
 */
@Service
public class ImageGenerationTools {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ModelPresetRepository presets;
    private final WorkspaceFileTools fileTools;
    private final Map<String, ImageGenAdapter> adapters;

    public ImageGenerationTools(ModelPresetRepository presets, WorkspaceFileTools fileTools,
                                DashscopeImageAdapter dashscope, OpenAiImageAdapter openai,
                                SiliconflowImageAdapter siliconflow) {
        this.presets = presets;
        this.fileTools = fileTools;
        this.adapters = Map.of(
                ModelPresetService.PROTOCOL_DASHSCOPE_IMAGE, dashscope,
                ModelPresetService.PROTOCOL_OPENAI_IMAGE, openai,
                ModelPresetService.PROTOCOL_SILICONFLOW_IMAGE, siliconflow);
    }

    /** 生图进度回调：发起生图请求时 onStart、结束（无论成败）时 onEnd，供 SSE 推送前端展示生成动画 */
    public interface ProgressListener {
        void onStart();

        void onEnd();
    }

    /** 按智能体的图像预设注册工具；未绑定或配置不完整时静默跳过（该智能体无生图能力） */
    public void register(Toolkit toolkit, Agent agent, ProgressListener listener) {
        String presetId = agent.getImagePresetId();
        if (presetId == null || presetId.isBlank()) {
            return;
        }
        ModelPreset preset = presets.findById(presetId).orElse(null);
        if (preset == null || !adapters.containsKey(preset.getProtocol())) {
            return;
        }
        if (isBlank(preset.getApiKey()) || isBlank(preset.getBaseUrl())) {
            return;
        }
        toolkit.registerTool(new GenerateImageTool(adapters.get(preset.getProtocol()), preset, fileTools, listener));
    }

    public static class GenerateImageTool {

        private final ImageGenAdapter adapter;
        private final ModelPreset preset;
        private final WorkspaceFileTools fileTools;
        private final ProgressListener listener;

        GenerateImageTool(ImageGenAdapter adapter, ModelPreset preset, WorkspaceFileTools fileTools,
                          ProgressListener listener) {
            this.adapter = adapter;
            this.preset = preset;
            this.fileTools = fileTools;
            this.listener = listener;
        }

        @Tool(description = "Generate an image from a text prompt and save it into the workspace. "
                + "Returns the saved absolute path of the image file. You MUST embed the returned path "
                + "in your reply as ![image](path) so the user can see the picture.")
        public String generateImage(
                @ToolParam(name = "prompt", description = "Image description in English for best quality")
                String prompt,
                @ToolParam(name = "size", required = false,
                        description = "Image size as WIDTHxHEIGHT, e.g. 1024x1024. Optional, defaults to 1024x1024")
                String size) {
            if (prompt == null || prompt.isBlank()) {
                return "生图失败：prompt 不能为空。";
            }
            String normalized = size == null || size.isBlank() ? "1024x1024" : size.trim();
            listener.onStart();
            try {
                ImageGenAdapter.Result result = adapter.generate(preset, prompt, normalized);
                Path saved = saveImage(result.data(), result.ext());
                return "图片已生成并保存：" + toForwardSlash(saved)
                        + "\n请在回复中原样引用该路径展示图片：![](" + toForwardSlash(saved) + ")";
            } catch (Exception e) {
                String reason = e.getMessage() == null ? e.toString() : e.getMessage();
                return "生图失败：" + reason + "。请检查图像预设配置（模型名/地址/密钥）后告知用户，不要反复重试。";
            } finally {
                listener.onEnd();
            }
        }

        private Path saveImage(byte[] data, String ext) throws Exception {
            Path dir = Paths.get(fileTools.primaryRoot(), "generated");
            Files.createDirectories(dir);
            Path target;
            do {
                target = dir.resolve("img_" + LocalDateTime.now().format(TS)
                        + "_" + String.format("%04x", RANDOM.nextInt(0x10000)) + "." + ext);
            } while (Files.exists(target));
            Files.write(target, data, StandardOpenOption.CREATE_NEW);
            return target;
        }

        private static String toForwardSlash(Path p) {
            return p.toAbsolutePath().toString().replace('\\', '/');
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
