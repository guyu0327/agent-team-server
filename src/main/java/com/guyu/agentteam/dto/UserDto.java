package com.guyu.agentteam.dto;

import com.guyu.agentteam.entity.User;

public record UserDto(String id, String name, String avatar, String signature) {

    public static UserDto from(User u) {
        return new UserDto(u.getId(), u.getName(), u.getAvatar(), u.getSignature());
    }
}
