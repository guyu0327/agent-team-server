package com.guyu.agentteam.dto;

/** 设置/更换群聊编排者：agentId 为空串/null 表示清除 */
public record HostRequest(String agentId) {
}
