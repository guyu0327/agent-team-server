package com.guyu.agentteam.dto;

import com.guyu.agentteam.entity.ModelPreset;

/**
 * 预设的 apiKey 会返回给前端：新建智能体时需要用它自动填充表单。
 * （智能体本身的 apiKey 仍然永不返回，区别对待是有意为之。）
 */
public record ModelPresetDto(String id, String name, String baseUrl, String apiKey, String remark) {

    public static ModelPresetDto from(ModelPreset p) {
        return new ModelPresetDto(p.getId(), p.getName(), p.getBaseUrl(), p.getApiKey(), p.getRemark());
    }
}
