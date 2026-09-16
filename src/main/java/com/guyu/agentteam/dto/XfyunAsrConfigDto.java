package com.guyu.agentteam.dto;

/**
 * 讯飞开放平台流式听写配置（appId/apiKey/apiSecret 均在控制台应用管理获取）。
 * 仅作内部配置载体与 PUT 请求体：密钥留空表示保持不变；响应一律走 AsrStreamStatusDto。
 */
public record XfyunAsrConfigDto(String appId, String apiKey, String apiSecret) {
}
