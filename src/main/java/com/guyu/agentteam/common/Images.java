package com.guyu.agentteam.common;

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;

/** 图片扩展名识别、MIME 推断与视觉块构建 */
public final class Images {

    /** 转成 ImageBlock 的单图上限（8MB），防止 base64 撑爆模型请求 */
    public static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;

    private static final Map<String, String> EXT_TO_MIME = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif",
            "webp", "image/webp",
            "bmp", "image/bmp");

    private Images() {
    }

    public static boolean isImage(String fileName) {
        return mediaType(fileName) != null;
    }

    /** 未知扩展名返回 null */
    public static String mediaType(String fileName) {
        if (fileName == null) return null;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return null;
        return EXT_TO_MIME.get(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** 从图片 URL 提取扩展名（不带点、小写），非图片后缀或解析不出回退 png；生图结果落盘命名用 */
    public static String extOfUrl(String url) {
        String path = url == null ? "" : url;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot > slash && dot < path.length() - 1 && mediaType(path) != null) {
            return path.substring(dot + 1).toLowerCase(Locale.ROOT);
        }
        return "png";
    }

    /**
     * 图片文件转模型可看的 ImageBlock；文件不存在、超限、格式不支持或读取失败返回 null。
     * 历史消息注入与 view_image 工具共用这一份（上限、MIME 判断、Base64 编码口径一致）。
     */
    public static ImageBlock toImageBlock(Path p, long maxBytes) {
        try {
            if (!Files.isRegularFile(p) || Files.size(p) > maxBytes) return null;
            String mime = mediaType(p.getFileName().toString());
            if (mime == null) return null;
            return ImageBlock.builder()
                    .source(Base64Source.builder()
                            .mediaType(mime)
                            .data(Base64.getEncoder().encodeToString(Files.readAllBytes(p)))
                            .build())
                    .build();
        } catch (IOException e) {
            return null;
        }
    }
}
