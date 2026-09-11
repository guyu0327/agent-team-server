package com.guyu.agentteam.dto;

import java.util.List;

public record MessagePageDto(List<MessageDto> list, boolean hasMore) {
}
