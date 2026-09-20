package com.guyu.agentteam.dto;

/** 上下文压缩设置：历史滚动摘要的开关与字符预算 */
public record ContextCompressionDto(boolean enabled, int budgetChars) {
}
