package com.guyu.agentteam.common;

import java.util.Locale;
import java.util.Map;

/** 图片扩展名识别与 MIME 推断 */
public final class Images {

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
}
