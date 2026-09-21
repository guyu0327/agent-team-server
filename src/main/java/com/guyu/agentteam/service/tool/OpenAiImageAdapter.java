package com.guyu.agentteam.service.tool;

import com.guyu.agentteam.entity.ModelPreset;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Base64;

/**
 * OpenAI Images 兼容文生图适配器。
 * API 地址填完整接口地址（官方惯例 https://api.openai.com/v1/images/generations）。
 * 响应兼容 url 与 b64_json 两种形态（dall-e-3 返回 url，gpt-image-1 返回 b64_json）。
 */
@Component
public class OpenAiImageAdapter extends AbstractImageAdapter {

    @Override
    public Result generate(ModelPreset preset, String prompt, String size) {
        ObjectNode body = MAPPER.createObjectNode()
                .put("model", preset.getName())
                .put("prompt", prompt)
                .put("size", size)
                .put("n", 1);

        String url = endpoint(preset);
        String responseBody = postJson(url, preset.getApiKey(), body.toString());

        JsonNode item;
        try {
            item = MAPPER.readTree(responseBody).path("data").path(0);
        } catch (Exception e) {
            throw new IllegalStateException("解析图像接口响应失败：" + e.getMessage(), e);
        }
        String link = item.path("url").asText("");
        if (!link.isBlank()) {
            byte[] data = download(link);
            return new Result(data, extOf(link));
        }
        String b64 = item.path("b64_json").asText("");
        if (!b64.isBlank()) {
            return new Result(Base64.getDecoder().decode(b64), "png");
        }
        throw new IllegalStateException("图像接口响应中没有图片结果：" + responseBody);
    }
}
