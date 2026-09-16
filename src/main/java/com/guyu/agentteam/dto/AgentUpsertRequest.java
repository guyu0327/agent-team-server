package com.guyu.agentteam.dto;

/**
 * 模型相关配置不再由智能体自身保存，统一通过 presetId 关联模型预设；
 * imagePresetId 可选，绑定的图像预设（文生图类）提供 generate_image 工具。
 */
public record AgentUpsertRequest(String name, String avatar, String groupName, String description,
                                 String presetId, String imagePresetId, Boolean isOrchestrator,
                                 String systemPrompt, Double temperature) {
}
