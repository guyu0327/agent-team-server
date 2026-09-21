package com.guyu.agentteam.service;

import com.guyu.agentteam.entity.AppSetting;
import com.guyu.agentteam.repository.AppSettingRepository;
import org.springframework.stereotype.Service;

/** app_settings 表读改存的样板收敛：读（键缺失或值为空白视为无值）、写（不存在则新建行） */
@Service
public class SettingsStore {

    private final AppSettingRepository settings;

    public SettingsStore(AppSettingRepository settings) {
        this.settings = settings;
    }

    /** 读取设置值；键不存在或值为空白返回 null */
    public String read(String key) {
        return settings.findById(key).map(AppSetting::getSettingValue)
                .filter(v -> v != null && !v.isBlank())
                .orElse(null);
    }

    /** 写入设置值（不存在则新建行） */
    public void write(String key, String value) {
        AppSetting s = settings.findById(key).orElseGet(() -> {
            AppSetting n = new AppSetting();
            n.setSettingKey(key);
            return n;
        });
        s.setSettingValue(value);
        s.setUpdatedAt(System.currentTimeMillis());
        settings.save(s);
    }
}
