package com.guyu.agentteam.dto;

import com.guyu.agentteam.common.Json;
import com.guyu.agentteam.entity.Message;

import java.util.List;

public record MessageDto(String id, String conversationId, String senderType, String senderId,
                         String content, List<Json.Attachment> attachments, String type, Long timestamp) {

    public static MessageDto from(Message m) {
        return new MessageDto(m.getId(), m.getConversationId(), m.getSenderType(), m.getSenderId(),
                m.getContent(), Json.readAttachments(m.getAttachments()), m.getType(), m.getCreatedAt());
    }
}
