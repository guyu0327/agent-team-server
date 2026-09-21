package com.guyu.agentteam.dto;

/** 微信通道状态 + 通道设置（一次往返） */
public record WechatStatusDto(boolean connected, boolean enabled, String botId, String ownerUserId,
                              boolean loginInProgress, String agentId, boolean autoWrite, boolean autoShell,
                              int maxReplyChars, int roundTimeoutMinutes) {
}
