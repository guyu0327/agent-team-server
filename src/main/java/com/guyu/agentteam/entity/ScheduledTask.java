package com.guyu.agentteam.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 定时任务：到点向绑定会话发送一条合成用户消息触发智能体回复。
 * kind=once 用 run_at；daily/weekly 用 time_of_day（weekly 另看 days_of_week，1=周一…7=周日）；
 * interval 用 interval_minutes。next_run_at 为调度依据，启动恢复与错过补发都围绕它进行。
 */
@Getter
@Setter
@Entity
@Table(name = "scheduled_tasks")
public class ScheduledTask {

    public static final String KIND_ONCE = "once";
    public static final String KIND_DAILY = "daily";
    public static final String KIND_WEEKLY = "weekly";
    public static final String KIND_INTERVAL = "interval";

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_PAUSED = "paused";
    public static final String STATUS_DONE = "done";

    /** 普通任务：执行智能体独立完成，绑定其专属任务线程 */
    public static final String MODE_NORMAL = "normal";
    /** 协作任务：编排者执行并拉协作成员建任务项目群一起完成 */
    public static final String MODE_COLLAB = "collab";

    @Id
    private String id;
    private String conversationId;
    private String agentId;
    private String name;
    private String content;
    private String kind;
    private Long runAt;
    private String timeOfDay;
    private String daysOfWeek;
    private Integer intervalMinutes;
    private Long nextRunAt;
    private Long lastRunAt;
    private String status;
    /** 错过的单次任务启动时是否 24h 内补发，默认不补发 */
    private boolean catchUp;
    private String mode;
    /** 后台触发回合自动放行写入/修改文件（无人盯审批，默认开） */
    private boolean autoWrite = true;
    /** 后台触发回合自动放行执行终端命令（风险更高，默认关） */
    private boolean autoShell = false;
    private Long createdAt;
}
