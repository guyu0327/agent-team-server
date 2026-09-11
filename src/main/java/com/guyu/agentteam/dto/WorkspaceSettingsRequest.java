package com.guyu.agentteam.dto;

import java.util.List;

/** 更新文件沙箱设置：root 可为相对路径，extraDirs 必须是绝对路径 */
public record WorkspaceSettingsRequest(String root, List<String> extraDirs) {
}
