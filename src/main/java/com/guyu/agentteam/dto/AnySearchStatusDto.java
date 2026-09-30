package com.guyu.agentteam.dto;

/**
 * AnySearch 联网搜索配置状态（API 唯一出口）：只回传密钥有无，永不回传明文。
 * 未配置 Key 时搜索走匿名免费档。
 */
public record AnySearchStatusDto(boolean hasKey) {
}
