package com.guyu.agentteam.dto;

import com.guyu.agentteam.entity.Agent;

public record AgentDto(String id, String name, String avatar, String groupName, String description,
                       String presetId, String imagePresetId, boolean isOrchestrator, String systemPrompt,
                       Double temperature) {

    public static AgentDto from(Agent a) {
        return new AgentDto(a.getId(), a.getName(), a.getAvatar(), a.getGroupName(), a.getDescription(),
                a.getPresetId() == null ? "" : a.getPresetId(),
                a.getImagePresetId() == null ? "" : a.getImagePresetId(),
                Boolean.TRUE.equals(a.getIsOrchestrator()), a.getSystemPrompt(), a.getTemperature());
    }
}
