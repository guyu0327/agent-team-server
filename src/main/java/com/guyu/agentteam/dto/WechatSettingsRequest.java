package com.guyu.agentteam.dto;

public record WechatSettingsRequest(boolean enabled, String agentId, boolean autoWrite, boolean autoShell,
                                    int maxReplyChars, int roundTimeoutMinutes) {
}
