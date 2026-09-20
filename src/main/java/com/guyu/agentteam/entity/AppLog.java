package com.guyu.agentteam.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** 运行日志：协作/讨论/审批/生图/错误等关键事件落库，供前端「查看日志」排查问题 */
@Getter
@Setter
@Entity
@Table(name = "app_logs")
public class AppLog {

    public static final String TYPE_ERROR = "error";
    public static final String TYPE_OP_REQUEST = "op_request";
    public static final String TYPE_OP_DECISION = "op_decision";
    public static final String TYPE_COORDINATION = "coordination";
    public static final String TYPE_DISCUSSION = "discussion";
    public static final String TYPE_IMAGE = "image";
    public static final String TYPE_COMPACT = "compact";
    public static final String TYPE_TASK = "task";
    public static final String TYPE_API_ERROR = "api_error";

    @Id
    private String id;
    private String type;
    private String conversationId;
    private String agentId;
    private String content;
    private Long createdAt;
}
