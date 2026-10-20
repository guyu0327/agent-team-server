package com.guyu.agentteam.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 会话级操作授权：审批卡片「本会话此类操作允许」的落库记录，行存在即该会话该类操作免询问 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "operation_grants")
@IdClass(OperationGrantId.class)
public class OperationGrant {

    public static final String OP_WRITE = "write";
    public static final String OP_EDIT = "edit";
    public static final String OP_SHELL = "shell";
    /** browser_open 的会话级授权：允许后该会话打开任意网页不再询问（后续点击/输入等跟进操作本就不再弹卡） */
    public static final String OP_BROWSER = "browser";

    public static final String DECISION_ONCE = "once";
    public static final String DECISION_CONVERSATION = "conversation";
    public static final String DECISION_DENY = "deny";
    /** 浏览器专用：把目标域名加入全局白名单并放行本次请求，白名单域名此后不再询问 */
    public static final String DECISION_WHITELIST = "whitelist";

    @Id
    private String conversationId;
    @Id
    private String opType;
    private Long grantedAt;
}
