package com.guyu.agentteam.dto;

import com.guyu.agentteam.entity.OperationGrant;

/** 会话级工具授权（审批卡片「本会话允许」落库记录）：opType 类型级授权，行存在即免询问 */
public record OpGrantDto(String opType, Long grantedAt) {

    public static OpGrantDto from(OperationGrant g) {
        return new OpGrantDto(g.getOpType(), g.getGrantedAt());
    }
}
