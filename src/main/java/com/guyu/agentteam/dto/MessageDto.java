package com.guyu.agentteam.dto;

import com.guyu.agentteam.common.Json;
import com.guyu.agentteam.entity.Message;

import java.util.List;
import java.util.Map;

public record MessageDto(String id, String conversationId, String senderType, String senderId,
                         String senderName, String senderRole,
                         String content, List<Json.Attachment> attachments, String type,
                         Long timestamp, String taskId, String taskName, List<Map<String, Object>> webActivity,
                         List<Map<String, Object>> browseActivity, List<Map<String, Object>> toolActivity,
                         String thinking) {

    public static MessageDto from(Message m) {
        return new MessageDto(m.getId(), m.getConversationId(), m.getSenderType(), m.getSenderId(),
                m.getSenderName(), m.getSenderRole(),
                m.getContent(), Json.readAttachments(m.getAttachments()), m.getType(),
                m.getCreatedAt(), m.getTaskId(), m.getTaskName(), Json.readWebActivity(m.getWebActivity()),
                Json.readBrowseActivity(m.getBrowseActivity()), Json.readToolActivity(m.getToolActivity()),
                m.getThinking());
    }
}
