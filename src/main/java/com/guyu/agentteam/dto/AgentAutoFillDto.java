package com.guyu.agentteam.dto;

/** 自动生成的基础六项：模型预设与技能不参与生成，仍由用户手动选择 */
public record AgentAutoFillDto(String name, String avatar, String groupName, String description,
                               String systemPrompt, Double temperature) {
}
