package com.guyu.agentteam.controller;

import com.guyu.agentteam.dto.UserDto;
import com.guyu.agentteam.dto.UserUpdateRequest;
import com.guyu.agentteam.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public UserDto me() {
        return UserDto.from(userService.getCurrent());
    }

    @PutMapping
    public UserDto update(@RequestBody UserUpdateRequest req) {
        return userService.update(req);
    }
}
