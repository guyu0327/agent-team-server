package com.guyu.agentteam.service;

import com.guyu.agentteam.common.ApiException;
import com.guyu.agentteam.common.Json;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 本机键鼠操控设置：持久化于 app_settings（key=computer.control）。
 * enabled：默认 false=不注册 computer_* 工具（智能体无法碰键鼠）；
 * 开启后智能体获得截屏/点击/按键/打字工具，且每次非只读操作都弹审批卡（人不在即拒绝）。
 */
@Service
public class ComputerSettingsService {

    private static final String KEY = "computer.control";

    private final SettingsStore store;
    private final ObjectMapper mapper = Json.mapper();

    public ComputerSettingsService(SettingsStore store) {
        this.store = store;
    }

    public record ComputerSettings(boolean enabled) {
    }

    public ComputerSettings load() {
        String json = store.read(KEY);
        if (json == null) {
            return new ComputerSettings(false);
        }
        try {
            ComputerSettings raw = mapper.readValue(json, ComputerSettings.class);
            return new ComputerSettings(raw.enabled());
        } catch (Exception e) {
            return new ComputerSettings(false);
        }
    }

    public ComputerSettings save(boolean enabled) {
        String json;
        try {
            json = mapper.writeValueAsString(new ComputerSettings(enabled));
        } catch (Exception e) {
            throw new ApiException(500, "保存本机操控设置失败");
        }
        store.write(KEY, json);
        return new ComputerSettings(enabled);
    }
}
