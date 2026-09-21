package com.guyu.agentteam.common;

/** 字符串判空 */
public final class Str {

    private Str() {
    }

    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
