package com.guyu.agentteam.dto;

/**
 * 讯飞流式听写配置状态（API 唯一出口）：只回传 appId 与密钥有无，永不回传密钥明文。
 * configured = 三项齐备，可直接使用实时转写。
 */
public record AsrStreamStatusDto(String appId, boolean hasKey, boolean hasSecret, boolean configured) {
}
