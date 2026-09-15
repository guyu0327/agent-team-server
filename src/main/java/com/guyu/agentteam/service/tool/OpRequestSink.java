package com.guyu.agentteam.service.tool;

/**
 * 受控操作（write_file/edit_file/execute）的审批入口：实现方弹卡片等待用户决定。
 * 会话 ID 由实现方在构造处绑定（编排者场景动态解析当前协作目标会话）。
 */
@FunctionalInterface
public interface OpRequestSink {

    /** 返回 true 表示用户已批准（允许一次/本会话允许），false 表示拒绝或超时未响应 */
    boolean request(String opType, String target, String detail);
}
