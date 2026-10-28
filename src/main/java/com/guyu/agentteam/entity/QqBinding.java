package com.guyu.agentteam.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * QQ 通道发送者 → 系统会话绑定：私聊键为 u:{用户openid}，群聊键为 g:{群openid}，
 * 同一发送者/群的消息固定路由到同一会话。
 */
@Getter
@Setter
@Entity
@Table(name = "qq_bindings")
public class QqBinding {

    @Id
    private String senderKey;
    private String conversationId;
    private Long createdAt;
}
