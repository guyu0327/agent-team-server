package com.guyu.agentteam.dto;

/**
 * 本机键鼠操控设置更新入参：enabled=true 后智能体获得键鼠工具
 * （会抢占焦点、覆盖剪贴板、截取全屏），每次操作仍需审批卡放行
 */
public record ComputerSettingsRequest(boolean enabled) {
}
