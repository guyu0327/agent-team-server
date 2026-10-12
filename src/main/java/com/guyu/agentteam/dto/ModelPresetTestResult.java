package com.guyu.agentteam.dto;

/** 测试连接结果：ok=false 时 message 为简要失败原因（完整异常链记运行日志） */
public record ModelPresetTestResult(boolean ok, long latencyMs, String reply, String message) {
}
