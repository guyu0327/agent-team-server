package com.guyu.agentteam.dto;

import com.guyu.agentteam.entity.ModelPreset;

/** 预设的 apiKey 永不返回前端，仅回传 hasKey 状态；更新时密钥留空表示保持不变 */
public record ModelPresetDto(String id, String name, String protocol, String baseUrl, boolean hasKey, String remark) {

    public static ModelPresetDto from(ModelPreset p) {
        boolean hasKey = p.getApiKey() != null && !p.getApiKey().isBlank();
        return new ModelPresetDto(p.getId(), p.getName(), p.getProtocol(), p.getBaseUrl(), hasKey, p.getRemark());
    }
}
