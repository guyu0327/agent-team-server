package com.guyu.agentteam.dto;

import java.util.List;

public record QqSettingsRequest(boolean enabled, String agentId, boolean autoWrite, boolean autoShell,
                                int maxReplyChars, int roundTimeoutMinutes,
                                String c2cPolicy, List<String> c2cWhitelist,
                                String groupPolicy, List<String> groupWhitelist) {
}
