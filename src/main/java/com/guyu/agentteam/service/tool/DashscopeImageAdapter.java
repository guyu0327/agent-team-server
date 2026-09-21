package com.guyu.agentteam.service.tool;

import com.guyu.agentteam.entity.ModelPreset;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 阿里云百炼 DashScope 文生图适配器（multimodal-generation 同步端点）。
 * API 地址填完整接口地址（官方惯例
 * https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation）。
 * 适用 z-image-turbo、qwen-image 等同步返回的模型；wanx 系列为异步任务接口，不支持。
 */
@Component
public class DashscopeImageAdapter extends AbstractImageAdapter {

    @Override
    public Result generate(ModelPreset preset, String prompt, String size) {
        ObjectNode content = MAPPER.createObjectNode().put("text", prompt);
        ObjectNode message = MAPPER.createObjectNode().put("role", "user");
        message.set("content", MAPPER.createArrayNode().add(content));
        ObjectNode input = MAPPER.createObjectNode();
        input.set("messages", MAPPER.createArrayNode().add(message));
        ObjectNode parameters = MAPPER.createObjectNode()
                .put("size", size.replace('x', '*'))
                .put("prompt_extend", false);
        ObjectNode body = MAPPER.createObjectNode()
                .put("model", preset.getName());
        body.set("input", input);
        body.set("parameters", parameters);

        String url = endpoint(preset);
        String responseBody = postJson(url, preset.getApiKey(), body.toString());
        JsonNode imageUrl = null;
        try {
            JsonNode contentArr = MAPPER.readTree(responseBody)
                    .path("output").path("choices").path(0).path("message").path("content");
            for (JsonNode part : contentArr) {
                if (part.hasNonNull("image") && !part.get("image").asText().isBlank()) {
                    imageUrl = part.get("image");
                    break;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("解析 DashScope 响应失败：" + e.getMessage(), e);
        }
        if (imageUrl == null) {
            throw new IllegalStateException("DashScope 响应中没有图片结果（该模型可能不属于同步生图接口）：" + responseBody);
        }
        String link = imageUrl.asText();
        return new Result(download(link), extOf(link));
    }
}
