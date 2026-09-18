package com.guyu.agentteam.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "conversations")
public class Conversation {

    @Id
    private String id;
    private String userId;
    private String type;
    private String name;
    /** 聊天模式：passive（默认）| free，仅群聊有意义 */
    private String chatMode;
    private boolean pinned;
    private String lastMessage;
    private Long lastMessageAt;
    private Long lastReadAt;
    private Long createdAt;
    private Long updatedAt;
    /** 历史会话归档时间：非空表示已归档（重置/解散/删除后进入历史），活跃查询一律排除 */
    private Long archivedAt;
}
