package com.guyu.agentteam.dto;

import com.guyu.agentteam.entity.ConversationFileGrant;

public record FileGrantDto(String path, String type, String name, Long grantedAt) {

    public static FileGrantDto from(ConversationFileGrant g) {
        return new FileGrantDto(g.getPath(), g.getType(), g.getName(), g.getGrantedAt());
    }
}
