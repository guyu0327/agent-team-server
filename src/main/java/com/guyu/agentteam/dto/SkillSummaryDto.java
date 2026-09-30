package com.guyu.agentteam.dto;

/** 技能列表项：agentCount 为绑定了该技能的智能体数量；global 为全局技能（所有智能体可用） */
public record SkillSummaryDto(String skillId, String name, String description, Long updatedAt, int agentCount,
                              boolean global) {
}
