package com.guyu.agentteam.dto;

import java.util.List;

public record LogPageDto(List<LogDto> list, boolean hasMore) {
}
