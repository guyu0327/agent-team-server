package com.guyu.agentteam.dto;

import com.guyu.agentteam.entity.Message;

public record MessageDto(String id, String conversationId, String senderType, String senderId,
                         String content, String type, Long timestamp) {

    public static MessageDto from(Message m) {
        return new MessageDto(m.getId(), m.getConversationId(), m.getSenderType(), m.getSenderId(),
                m.getContent(), m.getType(), m.getCreatedAt());
    }
}
