package com.guyu.agentteam.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "agents")
public class Agent {

    @Id
    private String id;
    private String userId;
    private String name;
    private String avatar;
    private String groupName;
    private String description;
    /** 关联的模型预设，模型的名称/地址/Key 都取自预设 */
    private String presetId;
    /** 为 true 时在聊天中作为团队编排者运行 AgentScope ReAct 循环 */
    private Boolean isOrchestrator;
    private String systemPrompt;
    private Double temperature;
    private Long createdAt;
    private Long updatedAt;
}
