package com.guyu.agentteam.dto;

/** 一句话自动生成智能体配置：prompt 为用户描述，presetId 指定用于生成的对话模型 */
public record AgentAutoFillRequest(String prompt, String presetId) {
}
