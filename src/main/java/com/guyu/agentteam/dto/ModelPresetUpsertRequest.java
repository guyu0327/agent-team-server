package com.guyu.agentteam.dto;

/**
 * 更新时 apiKey 传 null 或空串表示保持不变；protocol 传 null 或空串按对话预设（openai-chat）处理。
 */
public record ModelPresetUpsertRequest(String name, String protocol, String baseUrl, String apiKey, String remark) {
}
