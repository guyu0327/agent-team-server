package com.guyu.agentteam.service.tool;

import com.guyu.agentteam.entity.ModelPreset;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 阿里云百炼 DashScope 文生图适配器（multimodal-generation 同步端点）。
 * API 地址填完整接口地址（官方惯例
 * https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation）。
 * 适用 z-image-turbo、qwen-image 等同步返回的模型；wanx 系列为异步任务接口，不支持。
 */
@Component
public class DashscopeImageAdapter implements ImageGenAdapter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

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

    private String postJson(String url, String apiKey, String json) {
        HttpRequest request;
        HttpResponse<String> response;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("请求 DashScope 失败：" + e.getMessage(), e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("DashScope 返回 " + response.statusCode() + "（URL: " + url + "）：" + snippet(response.body()));
        }
        return response.body();
    }

    private byte[] download(String url) {
        HttpResponse<byte[]> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT).GET().build();
            response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("下载生成图片失败：" + e.getMessage(), e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("下载生成图片失败，HTTP " + response.statusCode());
        }
        return response.body();
    }

    /** API 地址即完整接口地址（官方文档原样复制），后端只去尾斜杠，不追加任何路径 */
    private static String endpoint(ModelPreset preset) {
        String base = preset.getBaseUrl().trim();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /** 取 URL 路径部分的扩展名，未知或非图片后缀回退 png */
    private static String extOf(String url) {
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot > slash && dot < path.length() - 1) {
            String ext = path.substring(dot + 1).toLowerCase();
            if (ext.matches("png|jpg|jpeg|webp|bmp|gif")) {
                return "jpg".equals(ext) ? "jpg" : ext;
            }
        }
        return "png";
    }

    private static String snippet(String body) {
        if (body == null) return "";
        return body.length() > 500 ? body.substring(0, 500) + "…" : body;
    }
}
