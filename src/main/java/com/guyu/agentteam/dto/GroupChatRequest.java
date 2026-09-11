package com.guyu.agentteam.dto;

import java.util.List;

public record GroupChatRequest(String name, List<String> memberIds) {
}
