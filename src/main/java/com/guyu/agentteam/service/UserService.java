package com.guyu.agentteam.service;

import com.guyu.agentteam.common.ApiException;
import com.guyu.agentteam.dto.UserDto;
import com.guyu.agentteam.dto.UserUpdateRequest;
import com.guyu.agentteam.entity.User;
import com.guyu.agentteam.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository users;

    public UserService(UserRepository users) {
        this.users = users;
    }

    public User getCurrent() {
        User u = users.findFirstByOrderByCreatedAtAsc();
        if (u == null) {
            throw ApiException.notFound("用户不存在");
        }
        return u;
    }

    @Transactional
    public UserDto update(UserUpdateRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw ApiException.badRequest("用户名不能为空");
        }
        User u = getCurrent();
        u.setName(req.name().trim());
        u.setSignature(req.signature() == null ? "" : req.signature().trim());
        u.setUpdatedAt(System.currentTimeMillis());
        users.save(u);
        return UserDto.from(u);
    }
}
