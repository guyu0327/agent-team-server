package com.guyu.agentteam.service.tool;

import com.guyu.agentteam.entity.ModelPreset;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Base64;

/**
 * 硅基流动（SiliconFlow）文生图适配器：请求/响应是自家方言——尺寸字段为
 * image_size（widthxheight）、数量字段为 batch_size，响应图片在 images[] 数组。
 * API 地址填完整接口地址（官方惯例 https://api.siliconflow.cn/v1/images/generations）。
 * 返回的图片 URL 一小时有效，须立即下载落盘。
 */
@Component
public class SiliconflowImageAdapter extends AbstractImageAdapter {

    @Override
    public Result generate(ModelPreset preset, String prompt, String size) {
        ObjectNode body = MAPPER.createObjectNode()
                .put("model", preset.getName())
                .put("prompt", prompt)
                .put("image_size", size)
                .put("batch_size", 1);

        String url = endpoint(preset);
        String responseBody = postJson(url, preset.getApiKey(), body.toString());

        JsonNode item;
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            item = root.path("images").path(0);
            if (item.isMissingNode() || item.isNull()) {
                item = root.path("data").path(0);
            }
        } catch (Exception e) {
            throw new IllegalStateException("解析图像接口响应失败：" + e.getMessage(), e);
        }
        String link = item.path("url").asText("");
        if (!link.isBlank()) {
            return new Result(download(link), extOf(link));
        }
        String b64 = item.path("b64_json").asText("");
        if (!b64.isBlank()) {
            return new Result(Base64.getDecoder().decode(b64), "png");
        }
        throw new IllegalStateException("图像接口响应中没有图片结果：" + responseBody);
    }
}
