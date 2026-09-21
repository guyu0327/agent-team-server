package com.guyu.agentteam.service.tool;

import com.guyu.agentteam.common.Images;
import com.guyu.agentteam.common.Json;
import com.guyu.agentteam.entity.ModelPreset;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** 生图适配器公共底座：HTTP 客户端与超时、POST JSON、生成图片下载、端点规整与错误截断 */
abstract class AbstractImageAdapter implements ImageGenAdapter {

    protected static final ObjectMapper MAPPER = Json.mapper();
    protected static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    protected static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    /** POST JSON 并返回 2xx 响应体；网络失败或非 2xx 抛 IllegalStateException（响应体截断 500 字符） */
    protected String postJson(String url, String apiKey, String json) {
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
            throw new IllegalStateException("请求图像接口失败（" + url + "）：" + e.getMessage(), e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("图像接口返回 " + response.statusCode() + "（URL: " + url + "）："
                    + snippet(response.body()));
        }
        return response.body();
    }

    protected byte[] download(String url) {
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
    protected static String endpoint(ModelPreset preset) {
        String base = preset.getBaseUrl().trim();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    protected static String snippet(String body) {
        if (body == null) return "";
        return body.length() > 500 ? body.substring(0, 500) + "…" : body;
    }

    protected static String extOf(String url) {
        return Images.extOfUrl(url);
    }
}
