package com.guyu.agentteam.dto;

import java.util.List;

/** content 与 attachments 至少一项非空；附件为绝对路径，类型由服务端判定 */
public record SendRequest(String content, List<AttachmentInput> attachments) {

    public record AttachmentInput(String path) {
    }
}
