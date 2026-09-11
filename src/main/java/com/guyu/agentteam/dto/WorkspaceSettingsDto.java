package com.guyu.agentteam.dto;

import java.util.List;

/** 文件沙箱设置：主工作区目录 + 白名单目录，均为当前生效的绝对路径 */
public record WorkspaceSettingsDto(String root, List<String> extraDirs) {
}
