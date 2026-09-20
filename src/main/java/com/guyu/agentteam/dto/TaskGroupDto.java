package com.guyu.agentteam.dto;

import java.util.List;

/** 任务页左侧列表项：一个任务会话与其下的全部任务（右侧按任务筛选与展示详情） */
public record TaskGroupDto(ConversationDto conversation, List<TaskDto> tasks) {
}
