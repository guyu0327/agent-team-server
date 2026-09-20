package com.guyu.agentteam.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** 智能体长期记忆：由智能体在对话中自行决定记录（框架 recordToMemory 工具写入），按智能体隔离、跨会话共享 */
@Getter
@Setter
@Entity
@Table(name = "agent_memories")
public class AgentMemory {

    @Id
    private String id;
    private String agentId;
    private String content;
    private Long createdAt;
}
