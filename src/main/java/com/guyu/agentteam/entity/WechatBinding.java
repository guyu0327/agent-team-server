package com.guyu.agentteam.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** 微信发送者 → 系统会话绑定：同一微信用户的消息固定路由到同一会话 */
@Getter
@Setter
@Entity
@Table(name = "wechat_bindings")
public class WechatBinding {

    /** iLink 用户 ID（如 o9cq806c-xxx@im.wechat） */
    @Id
    private String senderUserId;
    private String conversationId;
    private Long createdAt;
}
