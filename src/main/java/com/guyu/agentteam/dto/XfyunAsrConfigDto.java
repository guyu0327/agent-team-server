package com.guyu.agentteam.dto;

/** 讯飞开放平台流式听写配置（appId/apiKey/apiSecret 均在控制台应用管理获取） */
public record XfyunAsrConfigDto(String appId, String apiKey, String apiSecret) {
}
