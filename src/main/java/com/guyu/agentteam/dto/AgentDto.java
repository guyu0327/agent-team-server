package com.guyu.agentteam.dto;

import com.guyu.agentteam.entity.Agent;

import java.util.Arrays;
import java.util.List;

public record AgentDto(String id, String name, String avatar, String groupName, String description,
                       String presetId, String imagePresetId, boolean isOrchestrator, String systemPrompt,
                       Double temperature, List<String> skillIds) {

    public static AgentDto from(Agent a) {
        return new AgentDto(a.getId(), a.getName(), a.getAvatar(), a.getGroupName(), a.getDescription(),
                a.getPresetId() == null ? "" : a.getPresetId(),
                a.getImagePresetId() == null ? "" : a.getImagePresetId(),
                Boolean.TRUE.equals(a.getIsOrchestrator()), a.getSystemPrompt(), a.getTemperature(),
                skillIdsOf(a));
    }

    /** 逗号分隔存储转列表；空白返回空列表 */
    public static List<String> skillIdsOf(Agent a) {
        String raw = a.getSkillIds();
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
