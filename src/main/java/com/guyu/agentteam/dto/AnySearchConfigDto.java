package com.guyu.agentteam.dto;

/**
 * AnySearch 联网搜索配置。仅作内部配置载体与 PUT 请求体：
 * apiKey 留空表示保持不变；响应一律走 {@link AnySearchStatusDto}，永不回传密钥明文。
 */
public record AnySearchConfigDto(String apiKey) {
}
