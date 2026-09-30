package com.guyu.agentteam.dto;

import java.util.List;

/**
 * 技能详情：content 为剥掉 frontmatter 的 Markdown 正文；global 为全局技能（所有智能体可用）；
 * dir 为技能目录绝对路径（前端「打开资源管理器」用）；resources 为辅助文件相对路径清单（不含 SKILL.md/scope.json）。
 */
public record SkillDetailDto(String skillId, String name, String description, String content, Long updatedAt,
                             boolean global, String dir, List<String> resources) {
}
