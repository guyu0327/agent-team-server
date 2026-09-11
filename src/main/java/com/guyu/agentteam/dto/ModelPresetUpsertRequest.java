package com.guyu.agentteam.dto;

/**
 * 更新时 apiKey 传 null 或空串表示保持不变。
 */
public record ModelPresetUpsertRequest(String name, String baseUrl, String apiKey, String remark) {
}
