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

    public static final String DECISION_ONCE = "once";
    public static final String DECISION_CONVERSATION = "conversation";
    public static final String DECISION_DENY = "deny";

    @Id
    private String conversationId;
    @Id
    private String opType;
    private Long grantedAt;
}
