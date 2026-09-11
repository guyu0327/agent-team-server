package com.guyu.agentteam.dto;

import java.util.List;

public record ConversationDto(String id, String type, String name, String agentId,
                              List<String> memberIds, boolean pinned,
                              String lastMessage, Long lastMessageTime, long unreadCount) {
}
