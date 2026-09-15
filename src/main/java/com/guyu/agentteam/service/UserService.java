package com.guyu.agentteam.service;

import com.guyu.agentteam.common.ApiException;
import com.guyu.agentteam.common.Ids;
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
            // 私有化部署（SQLite）首启空库时自动创建默认账号，可在设置中改名
            u = new User();
            u.setId(Ids.next());
            u.setName("老板");
            u.setAvatar("");
            u.setSignature("");
            u.setCreatedAt(System.currentTimeMillis());
            u.setUpdatedAt(System.currentTimeMillis());
            u = users.save(u);
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
