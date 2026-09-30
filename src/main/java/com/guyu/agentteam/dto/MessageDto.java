package com.guyu.agentteam.dto;

import com.guyu.agentteam.common.Json;
import com.guyu.agentteam.entity.Message;

import java.util.List;
import java.util.Map;

public record MessageDto(String id, String conversationId, String senderType, String senderId,
                         String content, List<Json.Attachment> attachments, String type, Long timestamp,
                         String taskId, String taskName, List<Map<String, Object>> webActivity,
                         String thinking) {

    public static MessageDto from(Message m) {
        return new MessageDto(m.getId(), m.getConversationId(), m.getSenderType(), m.getSenderId(),
                m.getContent(), Json.readAttachments(m.getAttachments()), m.getType(), m.getCreatedAt(),
                m.getTaskId(), m.getTaskName(), Json.readWebActivity(m.getWebActivity()), m.getThinking());
    }
}
