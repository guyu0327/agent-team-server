package com.guyu.agentteam.dto;

import com.guyu.agentteam.common.ApiException;
import com.guyu.agentteam.entity.OperationGrant;

/** 审批卡片决定：requestId 对应一次受控操作请求，decision = once | conversation | deny */
public record OpGrantRequest(String requestId, String decision) {

    public void validate() {
        if (requestId == null || requestId.isBlank()) {
            throw ApiException.badRequest("缺少 requestId");
        }
        if (!OperationGrant.DECISION_ONCE.equals(decision)
                && !OperationGrant.DECISION_CONVERSATION.equals(decision)
                && !OperationGrant.DECISION_DENY.equals(decision)) {
            throw ApiException.badRequest("无效的决定：" + decision);
        }
    }
}
