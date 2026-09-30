package com.guyu.agentteam.dto;

/**
 * 技能创建/更新请求。skillId 仅创建时有效且即目录名；更新经路径传入、请求体中的 skillId 忽略。
 */
public record SkillUpsertRequest(String skillId, String name, String description, String content) {
}
