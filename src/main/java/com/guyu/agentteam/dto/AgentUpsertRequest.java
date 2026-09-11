package com.guyu.agentteam.dto;

/**
 * 模型相关配置不再由智能体自身保存，统一通过 presetId 关联模型预设。
 */
public record AgentUpsertRequest(String name, String avatar, String groupName, String description,
                                 String presetId, Boolean isOrchestrator, String systemPrompt, Double temperature) {
}
