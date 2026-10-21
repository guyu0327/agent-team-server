package com.guyu.agentteam.dto;

import java.util.List;

public record BrowserSettingsDto(boolean headless, boolean persistLogin, List<String> whitelist) {

    public static BrowserSettingsDto from(com.guyu.agentteam.service.BrowserSettingsService.BrowserSettings s) {
        return new BrowserSettingsDto(s.headless(), s.persistLogin(), s.whitelist());
    }
}
