package com.guyu.agentteam.dto;

/** 协作/自由讨论的时长限制（分钟） */
public record CoordinationLimitsDto(int overallMinutes, int memberMinutes) {
}
