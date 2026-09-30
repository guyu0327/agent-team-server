package com.guyu.agentteam.service;

import com.guyu.agentteam.common.Json;
import com.guyu.agentteam.common.SecretCipher;
import com.guyu.agentteam.common.Str;
import com.guyu.agentteam.dto.AnySearchConfigDto;
import com.guyu.agentteam.dto.AnySearchStatusDto;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * AnySearch 联网搜索：配置持久化于 app_settings（key=web.anysearchConfig，Key 经 DPAPI 加密），
 * API 层只回传 hasKey 状态；搜索走 MCP 兼容的 JSON-RPC 端点，未配 Key 时匿名免费档可用。
 */
@Service
public class AnySearchService {

    private static final String KEY_CONFIG = "web.anysearchConfig";
    private static final String ENDPOINT = "https://api.anysearch.com/mcp";

    private static final ObjectMapper MAPPER = Json.mapper();

    /** 独立客户端：必须跟随重定向（AbstractImageAdapter 的共享客户端未开，不能复用） */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final SettingsStore store;

    public AnySearchService(SettingsStore store) {
        this.store = store;
    }

    /** 解密后的 API Key；空串 = 匿名免费档 */
    public String apiKey() {
        String json = store.read(KEY_CONFIG);
        if (json == null) return "";
        try {
            AnySearchConfigDto cfg = MAPPER.readValue(json, AnySearchConfigDto.class);
            return Str.isBlank(cfg.apiKey()) ? "" : SecretCipher.decrypt(cfg.apiKey());
        } catch (Exception e) {
            return "";
        }
    }

    public AnySearchStatusDto status() {
        return new AnySearchStatusDto(!Str.isBlank(apiKey()));
    }

    /** 保存：apiKey 留空 = 保持现有配置不变 */
    public AnySearchStatusDto save(AnySearchConfigDto req) {
        String key = req == null || req.apiKey() == null ? "" : req.apiKey().trim();
        if (key.isEmpty()) return status();
        AnySearchConfigDto stored = new AnySearchConfigDto(SecretCipher.encrypt(key));
        store.write(KEY_CONFIG, MAPPER.writeValueAsString(stored));
        return status();
    }

    public AnySearchStatusDto clear() {
        store.write(KEY_CONFIG, "");
        return status();
    }

    /**
     * 调用 tools/call search，返回 result.content[0].text（服务端已排版好的 Markdown 搜索结果）。
     * 失败抛 IllegalStateException（中文消息），由调用方转为可读文本反馈给智能体。
     */
    public String search(String query, int maxResults) {
        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "tools/call",
                "params", Map.of(
                        "name", "search",
                        "arguments", Map.of("query", query, "max_results", maxResults)));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        String key = apiKey();
        if (!Str.isBlank(key)) builder.header("Authorization", "Bearer " + key);

        HttpResponse<String> response;
        try {
            response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("请求搜索服务失败：" + e.getMessage(), e);
        }
        if (response.statusCode() == 401 || response.statusCode() == 403 || response.statusCode() == 429) {
            throw new IllegalStateException("搜索服务返回 " + response.statusCode()
                    + "（匿名额度限流或 Key 鉴权失败），可在 设置→联网搜索 配置或更换 API Key");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("搜索服务返回 " + response.statusCode() + "：" + snippet(response.body()));
        }
        try {
            JsonNode root = MAPPER.readTree(response.body());
            JsonNode err = root.path("error");
            if (!err.isMissingNode() && !err.isNull()) {
                throw new IllegalStateException("搜索服务错误：" + err.path("message").asText(snippet(response.body())));
            }
            String text = root.path("result").path("content").path(0).path("text").asText("");
            if (text.isBlank()) {
                throw new IllegalStateException("搜索服务返回了空结果：" + snippet(response.body()));
            }
            return text;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("解析搜索结果失败：" + e.getMessage(), e);
        }
    }

    private static String snippet(String body) {
        if (body == null) return "";
        return body.length() > 500 ? body.substring(0, 500) + "…" : body;
    }
}
