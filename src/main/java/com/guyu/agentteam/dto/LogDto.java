package com.guyu.agentteam.dto;

import com.guyu.agentteam.entity.AppLog;

public record LogDto(String id, String type, String conversationId, String agentId,
                     String content, Long createdAt) {

    public static LogDto from(AppLog l) {
        return new LogDto(l.getId(), l.getType(), l.getConversationId(), l.getAgentId(),
                l.getContent(), l.getCreatedAt());
    }
}
