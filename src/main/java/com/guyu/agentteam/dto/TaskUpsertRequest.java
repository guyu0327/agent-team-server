package com.guyu.agentteam.dto;

import java.util.List;

/**
 * 定时任务创建/更新入参。kind = once | daily | weekly | interval：
 * once 必填 runAt（毫秒时间戳）；daily/weekly 必填 timeOfDay（HH:mm）；weekly 另填 daysOfWeek（"1,3,5"，1=周一）；
 * interval 必填 intervalMinutes。mode = normal（普通，智能体独立执行）| collab（协作，编排者拉成员建群）：
 * memberIds 仅创建时有意义（协作任务的协作成员，非编排者智能体）；catchUp 控制错过的单次任务是否启动补发（默认不补发）；
 * autoWrite/autoShell 控制后台触发回合是否自动放行写改文件/执行命令（默认 写改=开、命令=关），手动执行回合不套用。
 */
public record TaskUpsertRequest(String agentId, List<String> memberIds, String name, String content,
                                String kind, Long runAt, String timeOfDay, String daysOfWeek,
                                Integer intervalMinutes, String status, String mode, Boolean catchUp,
                                Boolean autoWrite, Boolean autoShell) {
}
