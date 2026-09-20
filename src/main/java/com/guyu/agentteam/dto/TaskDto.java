package com.guyu.agentteam.dto;

import com.guyu.agentteam.entity.ScheduledTask;

public record TaskDto(String id, String conversationId, String agentId, String name, String content,
                      String kind, Long runAt, String timeOfDay, String daysOfWeek, Integer intervalMinutes,
                      Long nextRunAt, Long lastRunAt, String status, String mode, boolean catchUp,
                      boolean autoWrite, boolean autoShell, Long createdAt) {

    public static TaskDto from(ScheduledTask t) {
        return new TaskDto(t.getId(), t.getConversationId(), t.getAgentId(), t.getName(), t.getContent(),
                t.getKind(), t.getRunAt(), t.getTimeOfDay(), t.getDaysOfWeek(), t.getIntervalMinutes(),
                t.getNextRunAt(), t.getLastRunAt(), t.getStatus(), t.getMode(), t.isCatchUp(),
                t.isAutoWrite(), t.isAutoShell(), t.getCreatedAt());
    }
}
