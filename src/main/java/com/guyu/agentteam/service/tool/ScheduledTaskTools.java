package com.guyu.agentteam.service.tool;

import com.guyu.agentteam.dto.TaskDto;
import com.guyu.agentteam.dto.TaskUpsertRequest;
import com.guyu.agentteam.entity.ScheduledTask;
import com.guyu.agentteam.service.ScheduledTaskService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 定时任务工具：智能体在对话中自主创建/修改/查询/取消定时任务。
 * 时间不用 cron——结构化字段（kind 枚举 + HH:mm / 星期数字）对模型可靠得多。
 * 任务绑定当前智能体的任务线程；memberIds 非空则建任务项目群拉成员一起执行。
 */
@Service
public class ScheduledTaskTools {

    private final ScheduledTaskService scheduledTasks;

    public ScheduledTaskTools(ScheduledTaskService scheduledTasks) {
        this.scheduledTasks = scheduledTasks;
    }

    public void register(Toolkit toolkit, Supplier<String> conversationId, Supplier<String> agentId,
                         Supplier<String> activeTriggerTaskName) {
        toolkit.registerTool(new Instance(scheduledTasks, conversationId, agentId, activeTriggerTaskName));
    }

    private static class Instance {

        private final ScheduledTaskService scheduledTasks;
        private final Supplier<String> conversationId;
        private final Supplier<String> agentId;
        /** 非空表示当前回合是某定时任务的触发执行，此时禁止再创建任务（防止把触发误当新请求反复建任务） */
        private final Supplier<String> activeTriggerTaskName;

        Instance(ScheduledTaskService scheduledTasks, Supplier<String> conversationId, Supplier<String> agentId,
                 Supplier<String> activeTriggerTaskName) {
            this.scheduledTasks = scheduledTasks;
            this.conversationId = conversationId;
            this.agentId = agentId;
            this.activeTriggerTaskName = activeTriggerTaskName;
        }

        @Tool(description = "Create a scheduled task that automatically sends a message to your dedicated task "
                + "conversation at the given time, and you (possibly with invited members) will handle it then. "
                + "kind must be one of: once (run_at time, for reminders and one-off jobs), daily (every day at "
                + "time_of_day), weekly (on days_of_week at time_of_day), interval (every interval_minutes minutes). "
                + "Time format: time_of_day is 24h 'HH:mm'. days_of_week uses digits 1-7 where 1=Monday, e.g. '1,3,5'. "
                + "Pass member_ids when other team members must take part in each run (e.g. recurring team workflows "
                + "like 'every day give X a task and have Y test it') — the task project group is created together "
                + "with the task, and every run happens in that existing group (do NOT create it again at fire time). "
                + "NEVER substitute a plain group chat (create_team) for "
                + "a scheduled task: a group chat does not make work happen on schedule. "
                + "Confirm the schedule to the user after creating.")
        public String scheduleTask(
                @ToolParam(name = "name", required = true, description = "Short task name, e.g. '喝水提醒' or '每周周报'")
                String name,
                @ToolParam(name = "content", required = true,
                        description = "The message content that will be sent to you when the task fires. It must be "
                                + "self-contained: include all context, requirements and expected output, because you "
                                + "will handle it later without this conversation's context")
                String content,
                @ToolParam(name = "kind", required = true,
                        description = "one of: once | daily | weekly | interval")
                String kind,
                @ToolParam(name = "run_at", required = false,
                        description = "Epoch milliseconds. Required when kind=once")
                Long runAt,
                @ToolParam(name = "time_of_day", required = false,
                        description = "'HH:mm' 24h local time. Required for daily/weekly")
                String timeOfDay,
                @ToolParam(name = "days_of_week", required = false,
                        description = "Comma separated digits 1-7 (1=Monday), e.g. '1,3,5'. Required for weekly")
                String daysOfWeek,
                @ToolParam(name = "interval_minutes", required = false,
                        description = "Interval in minutes (>=1). Required for interval kind")
                Integer intervalMinutes,
                @ToolParam(name = "member_ids", required = false,
                        description = "Agent IDs or member names of members to invite into a task project group. "
                                + "Leave empty only if you can complete the task alone")
                List<String> memberIds,
                @ToolParam(name = "auto_write", required = false,
                        description = "Whether background trigger rounds may auto-approve write/edit file operations "
                                + "without anyone reviewing them. Default true. Disclose this policy to the user")
                Boolean autoWrite,
                @ToolParam(name = "auto_shell", required = false,
                        description = "Whether background trigger rounds may auto-approve shell command execution. "
                                + "Default false: without it, shell commands are rejected in background rounds")
                Boolean autoShell) {
            String trigger = activeTriggerTaskName.get();
            if (trigger != null) {
                return "当前回合是定时任务「" + trigger + "」到点的自动触发，该任务已存在并正在执行，"
                        + "请直接执行触发消息里的任务内容，不要再创建定时任务。";
            }
            TaskDto t = scheduledTasks.create(com.guyu.agentteam.common.CurrentUser.ID, new TaskUpsertRequest(
                    agentId.get(), memberIds, name, content, kind, runAt, timeOfDay, daysOfWeek, intervalMinutes,
                    null, null, null, autoWrite, autoShell));
            return "定时任务已创建：" + t.name() + "（" + kindLabel(t) + "），下次触发时间戳 " + t.nextRunAt()
                    + "。后台触发回合的受控操作策略：写改文件" + (t.autoWrite() ? "自动放行" : "自动拒绝")
                    + "、终端命令" + (t.autoShell() ? "自动放行" : "自动拒绝")
                    + "。请向用户确认任务内容、时间和该策略。";
        }

        @Tool(description = "List all scheduled tasks belonging to you (the current agent), including their "
                + "schedule, status and next run time.")
        public String listScheduledTasks() {
            List<TaskDto> list = scheduledTasks.listByAgent(agentId.get());
            if (list.isEmpty()) {
                return "当前没有定时任务。";
            }
            return list.stream()
                    .map(t -> "- [" + t.id() + "] " + t.name() + "：" + kindLabel(t)
                            + "，状态 " + t.status() + "，下次触发时间戳 " + t.nextRunAt())
                    .collect(Collectors.joining("\n"));
        }

        @Tool(description = "Update one of your scheduled tasks by id. Only provided fields are changed. "
                + "Set status to 'paused' to pause it or 'active' to resume.")
        public String updateScheduledTask(
                @ToolParam(name = "task_id", required = true, description = "Task id from list_scheduled_tasks")
                String taskId,
                @ToolParam(name = "name", required = false, description = "New task name") String name,
                @ToolParam(name = "content", required = false, description = "New message content") String content,
                @ToolParam(name = "run_at", required = false, description = "New epoch millis (once kind)") Long runAt,
                @ToolParam(name = "time_of_day", required = false, description = "New 'HH:mm' (daily/weekly)") String timeOfDay,
                @ToolParam(name = "days_of_week", required = false, description = "New days digits 1-7 (weekly)") String daysOfWeek,
                @ToolParam(name = "interval_minutes", required = false, description = "New interval minutes") Integer intervalMinutes,
                @ToolParam(name = "status", required = false, description = "'active' or 'paused'") String status) {
            TaskDto t = scheduledTasks.update(taskId, new TaskUpsertRequest(
                    null, null, name, content, null, runAt, timeOfDay, daysOfWeek, intervalMinutes, status,
                    null, null, null, null));
            return "定时任务已更新：" + t.name() + "（" + kindLabel(t) + "），状态 " + t.status() + "。";
        }

        @Tool(description = "Cancel and delete one of your scheduled tasks by id.")
        public String cancelScheduledTask(
                @ToolParam(name = "task_id", required = true, description = "Task id from list_scheduled_tasks")
                String taskId) {
            scheduledTasks.delete(taskId);
            return "定时任务已取消。";
        }

        private static String kindLabel(TaskDto t) {
            return switch (t.kind()) {
                case ScheduledTask.KIND_ONCE -> "单次（时间戳 " + t.runAt() + "）";
                case ScheduledTask.KIND_DAILY -> "每天 " + t.timeOfDay();
                case ScheduledTask.KIND_WEEKLY -> "每周 " + t.daysOfWeek() + " " + t.timeOfDay();
                case ScheduledTask.KIND_INTERVAL -> "每 " + t.intervalMinutes() + " 分钟";
                default -> t.kind();
            };
        }
    }

    /**
     * 拼进系统提示的「创建定时任务」指令（成员与编排者两个入口同源，改文案只改这里）。
     * 任务项目群在创建任务时即建好，到点直接在群里执行——不要写成「到点再建群」。
     */
    public static String scheduleHint(boolean orchestrator) {
        if (orchestrator) {
            return "当用户要求定时、定期、每天/每周固定时间、每隔一段时间或到点执行/提醒某事时，这是要创建定时任务："
                    + "必须调用 schedule_task 创建并向用户确认时间；只要该工作需要成员参与（由谁做、让谁做、谁来完成等），"
                    + "就必须把相关成员传给 member_ids（支持成员 id 或成员名字，可先 list_team 查成员）——"
                    + "创建任务时会同时建好任务项目群，到点直接在该群中执行，无需到点再建。"
                    + "漏传 member_ids 会导致只有你一人执行，成员不会参与。"
                    + "此场景下禁止调用 create_team 建普通项目群、也不要立即 delegate 委派成员：普通群聊本身不会让工作定时发生。"
                    + "create_team 仅用于「现在就执行」的即时协作请求。"
                    + "已创建的任务可用 list_scheduled_tasks 查询、update_scheduled_task 修改、cancel_scheduled_task 取消，"
                    + "任务的执行结果会出现在「定时任务」页对应的任务会话中。";
        }
        return "当用户要求定时、定期、每天/每周固定时间、每隔一段时间或到点执行/提醒某事时，必须调用 schedule_task "
                + "创建定时任务并向用户确认时间，不要只在对话里口头答应；"
                + "若该工作需要其他成员一起完成，把成员 id 传给 member_ids——"
                + "创建任务时会同时建好任务项目群，到点直接在该群中执行，无需到点再建。"
                + "不要用建普通群聊代替定时任务（群聊本身不会让工作定时发生）。"
                + "任务的执行结果会出现在「定时任务」页对应的任务会话中。";
    }

    /** 拼进系统提示的「定时任务触发识别」指令（与 scheduleHint 同源配套） */
    public static String triggerHint(boolean orchestrator) {
        if (orchestrator) {
            return "收到以「【定时任务触发·」开头的消息时，说明是某个已存在任务到点的自动触发：按协作规范真实执行其中的任务内容——"
                    + "需要成员参与就实际调用 delegate（触发消息所在的会话通常已是任务项目群，此时直接 delegate，不要再 create_team），"
                    + "需要写改文件就实际调用文件工具；此场景禁止的只有调用 schedule_task 再创建新任务，其余协作工具照常使用。"
                    + "任务内容若说「以任务卡形式委派」，指把任务卡内容作为 delegate 的参数传给成员，绝不是把任务卡作为聊天文本输出。"
                    + "不要只在回复里输出任务卡、计划或总结来代替真实执行，也不要因为历史记录里出现过类似执行结果就跳过本轮。";
        }
        return "收到以「【定时任务触发·」开头的消息时，说明是某个已存在任务到点的自动触发：直接执行其中的任务内容，"
                + "禁止调用 schedule_task 再创建新任务。";
    }
}
