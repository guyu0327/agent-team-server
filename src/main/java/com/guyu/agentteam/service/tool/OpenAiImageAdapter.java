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
import java.util.Base64;

/**
 * OpenAI Images 兼容文生图适配器。
 * API 地址填完整接口地址（官方惯例 https://api.openai.com/v1/images/generations）。
 * 响应兼容 url 与 b64_json 两种形态（dall-e-3 返回 url，gpt-image-1 返回 b64_json）。
 */
@Component
public class OpenAiImageAdapter implements ImageGenAdapter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    @Override
    public Result generate(ModelPreset preset, String prompt, String size) {
        ObjectNode body = MAPPER.createObjectNode()
                .put("model", preset.getName())
                .put("prompt", prompt)
                .put("size", size)
                .put("n", 1);

        String url = endpoint(preset);
        HttpRequest request;
        HttpResponse<String> response;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + preset.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("请求图像接口失败：" + e.getMessage(), e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String detail = response.body();
            throw new IllegalStateException("图像接口返回 " + response.statusCode() + "（URL: " + url + "）："
                    + (detail == null ? "" : detail.length() > 500 ? detail.substring(0, 500) + "…" : detail));
        }

        JsonNode item;
        try {
            item = MAPPER.readTree(response.body()).path("data").path(0);
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
        throw new IllegalStateException("图像接口响应中没有图片结果：" + response.body());
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

    private static String extOf(String url) {
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot > slash && dot < path.length() - 1) {
            String ext = path.substring(dot + 1).toLowerCase();
            if (ext.matches("png|jpg|jpeg|webp|bmp|gif")) {
                return ext;
            }
        }
        return "png";
    }
}
