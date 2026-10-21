package com.guyu.agentteam.dto;

/**
 * 测试连接请求：携带表单当前值（可未保存）。
 * apiKey 留空且 presetId 非空时取库内已存密钥（编辑态「保持不变」场景）；
 * reasoningEffort 留空表示不发送该参数。
 */
public record ModelPresetTestRequest(String presetId, String name, String protocol, String baseUrl, String apiKey,
                                     String reasoningEffort) {
}
