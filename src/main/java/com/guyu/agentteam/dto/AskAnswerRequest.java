package com.guyu.agentteam.dto;

import com.guyu.agentteam.common.ApiException;

/** 提问卡片回答：requestId 对应一个问题，answer 为所选项文本或「其他」自由输入 */
public record AskAnswerRequest(String requestId, String answer) {

    public void validate() {
        if (requestId == null || requestId.isBlank()) {
            throw ApiException.badRequest("缺少 requestId");
        }
    }
}
