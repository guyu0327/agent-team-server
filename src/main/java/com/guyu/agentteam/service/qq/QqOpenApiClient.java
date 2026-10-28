package com.guyu.agentteam.service.qq;

import com.guyu.agentteam.common.ApiException;
import com.guyu.agentteam.common.Json;
import com.guyu.agentteam.common.Str;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

/**
 * QQ 官方开放平台 Bot API 客户端（协议对照 bot.q.qq.com/wiki）：
 * getAppAccessToken 换取凭证（平台返回的 expires_in 是字符串秒数，缓存提前 2 分钟刷新）、
 * v2 单聊/群聊发消息——被动回复必须回传事件携带的 msg_id（5 分钟窗口内最多 5 条），msg_seq 区分多条；
 * 图片小图走 /files 内联直传（base64 file_data），大图走分片上传
 * （upload_prepare → 逐片 PUT 到 COS 预签名地址 → upload_part_finish → /files 合并）。
 */
@Component
public class QqOpenApiClient {

    public static final String TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken";
    public static final String API_BASE = "https://api.sgroup.qq.com";

    private static final Duration API_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration PUT_TIMEOUT = Duration.ofSeconds(60);
    /** 图片软限 20MB；md5_10m 覆盖文件前 10002432 字节；file_size/block_size 平台要求 JSON 字符串 */
    private static final int MD5_TEN_M_BYTES = 10002432;
    private static final long DEFAULT_BLOCK_SIZE = 5L * 1024 * 1024;
    /** 此大小以内走 file_data 内联直传（base64 后约 4MB；更大的内联体平台会答 850012 内部代理错误） */
    private static final long INLINE_MAX_BYTES = 3L * 1024 * 1024;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final Object tokenLock = new Object();
    private String tokenAppId;
    private String tokenSecret;
    private String cachedToken;
    private long tokenExpiresAt;

    /** 换取并缓存 access_token；appId+secret 变化时自动重新获取，获取失败抛业务异常（含平台错误码） */
    public String accessToken(String appId, String appSecret) {
        synchronized (tokenLock) {
            long now = System.currentTimeMillis();
            if (cachedToken != null && appId.equals(tokenAppId) && appSecret.equals(tokenSecret)
                    && now < tokenExpiresAt) {
                return cachedToken;
            }
            JsonNode resp = postJson(TOKEN_URL, Json.mapper().writeValueAsString(Map.of(
                    "appId", appId, "clientSecret", appSecret)), null);
            String token = resp.path("access_token").asText(null);
            if (Str.isBlank(token)) {
                throw ApiException.badRequest("获取 QQ 凭证失败（" + resp.path("code").asText("")
                        + "）：" + resp.path("message").asText("未知错误"));
            }
            long expiresIn = resp.path("expires_in").asLong(7200);
            tokenAppId = appId;
            tokenSecret = appSecret;
            cachedToken = token;
            tokenExpiresAt = now + Math.max(60, expiresIn - 120) * 1000L;
            return token;
        }
    }

    /** 私聊被动回复（msg_id 为收到消息的 id，msg_seq 从 1 递增，超过 5 条平台会拒收） */
    public void sendC2cText(String token, String openid, String content, String msgId, int msgSeq) {
        send("/v2/users/" + enc(openid) + "/messages", token, Json.mapper().writeValueAsString(Map.of(
                "content", content, "msg_type", 0, "msg_id", msgId, "msg_seq", msgSeq)));
    }

    /** 群聊被动回复（回传群消息事件里的 msg_id） */
    public void sendGroupText(String token, String groupOpenid, String content, String msgId, int msgSeq) {
        send("/v2/groups/" + enc(groupOpenid) + "/messages", token, Json.mapper().writeValueAsString(Map.of(
                "content", content, "msg_type", 0, "msg_id", msgId, "msg_seq", msgSeq)));
    }

    /**
     * 上传图片换取 file_info（官方分片协议，file_type 固定 1=图片、软限 20MB）。
     * kind=c2c 走 /v2/users/{openid}、kind=group 走 /v2/groups/{openid}——文件不跨场景，回复哪个会话就传哪个端点。
     */
    public String uploadImage(String token, String kind, String openid, Path file) {
        byte[] data;
        try {
            data = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new IllegalStateException("读取待发图片失败：" + file + "：" + e.getMessage(), e);
        }
        String scene = ("group".equals(kind) ? "/v2/groups/" : "/v2/users/") + enc(openid);
        // 小图优先内联直传：base64 直接进 /files，免去分片四步——小文件走分片反而常被平台答 850012
        if (data.length <= INLINE_MAX_BYTES) {
            return mergeFileInfo(token, API_BASE + scene + "/files", Json.mapper().writeValueAsString(Map.of(
                    "file_type", 1,
                    "file_data", Base64.getEncoder().encodeToString(data),
                    "srv_send_msg", false)));
        }
        JsonNode prep;
        try {
            prep = postJson(API_BASE + scene + "/upload_prepare", Json.mapper().writeValueAsString(Map.of(
                    "file_type", 1,
                    "file_size", String.valueOf(data.length),
                    "file_name", file.getFileName().toString(),
                    "md5", digestHex("MD5", data, 0, data.length),
                    "sha1", digestHex("SHA-1", data, 0, data.length),
                    "md5_10m", digestHex("MD5", data, 0, Math.min(data.length, MD5_TEN_M_BYTES)))), token);
        } catch (RuntimeException e) {
            throw new IllegalStateException("QQ 图片上传准备请求失败：" + e.getMessage(), e);
        }
        int code = prep.path("code").asInt(0);
        if (code != 0) {
            throw new IllegalStateException("QQ 图片上传准备失败（" + code + "）：" + prep.path("message").asText(""));
        }
        if (!prep.has("upload_id") && prep.has("data")) {
            prep = prep.path("data");
        }
        String uploadId = prep.path("upload_id").asText(null);
        if (Str.isBlank(uploadId)) {
            throw new IllegalStateException("QQ 图片上传准备响应缺少 upload_id：" + prep);
        }
        // parts 可为空（平台已有同内容文件时直接合并即可）；分片 index 平台按 1 起算，偏移随之推导
        long blockSize = prep.path("block_size").asLong(DEFAULT_BLOCK_SIZE);
        int fallbackIndex = 1;
        for (JsonNode part : prep.path("parts")) {
            int index = part.path("index").asInt(fallbackIndex);
            long offset = (long) Math.max(index - 1, 0) * blockSize;
            long size = part.path("block_size").asLong(blockSize);
            if (size <= 0) {
                size = DEFAULT_BLOCK_SIZE;
            }
            int end = (int) Math.min(offset + size, data.length);
            byte[] chunk = Arrays.copyOfRange(data, (int) offset, end);
            putToCos(part.path("presigned_url").asText(""), chunk);
            // 每片 PUT 成功后单独确认；该接口响应无 body，HTTP 非 2xx 时 postJson 已抛错
            try {
                postJson(API_BASE + scene + "/upload_part_finish", Json.mapper().writeValueAsString(Map.of(
                        "upload_id", uploadId,
                        "part_index", index,
                        "block_size", String.valueOf(chunk.length),
                        "md5", digestHex("MD5", chunk, 0, chunk.length))), token);
            } catch (RuntimeException e) {
                throw new IllegalStateException("QQ 图片分片确认（第 " + index + " 片）失败：" + e.getMessage(), e);
            }
            fallbackIndex = index + 1;
        }
        return mergeFileInfo(token, API_BASE + scene + "/files", Json.mapper().writeValueAsString(Map.of(
                "file_type", 1, "upload_id", uploadId,
                "file_name", file.getFileName().toString(), "srv_send_msg", false)));
    }

    /** 调 /files 合并（或内联直传）换 file_info；响应可能是 data 包裹，两种形态都容忍 */
    private String mergeFileInfo(String token, String url, String body) {
        JsonNode merged;
        try {
            merged = postJson(url, body, token);
        } catch (RuntimeException e) {
            throw new IllegalStateException("QQ 图片合并请求失败：" + e.getMessage(), e);
        }
        int code = merged.path("code").asInt(0);
        if (code != 0) {
            throw new IllegalStateException("QQ 图片合并失败（" + code + "）：" + merged.path("message").asText(""));
        }
        if (!merged.has("file_info") && merged.has("data")) {
            merged = merged.path("data");
        }
        String fileInfo = merged.path("file_info").asText(null);
        if (Str.isBlank(fileInfo)) {
            throw new IllegalStateException("QQ 图片合并响应缺少 file_info：" + merged);
        }
        return fileInfo;
    }

    /** 单聊发图（msg_type=7 富媒体，media.file_info 来自 uploadImage） */
    public void sendC2cImage(String token, String openid, String fileInfo, String msgId, int msgSeq) {
        send("/v2/users/" + enc(openid) + "/messages", token, Json.mapper().writeValueAsString(Map.of(
                "content", "", "msg_type", 7, "media", Map.of("file_info", fileInfo),
                "msg_id", msgId, "msg_seq", msgSeq)));
    }

    /** 群聊发图：群聊 content 为必填项（空串即可） */
    public void sendGroupImage(String token, String groupOpenid, String fileInfo, String msgId, int msgSeq) {
        send("/v2/groups/" + enc(groupOpenid) + "/messages", token, Json.mapper().writeValueAsString(Map.of(
                "content", "", "msg_type", 7, "media", Map.of("file_info", fileInfo),
                "msg_id", msgId, "msg_seq", msgSeq)));
    }

    /** 图片分片直传 COS 预签名地址：签名按裸请求计算，不能追加任何认证头 */
    private void putToCos(String url, byte[] chunk) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(PUT_TIMEOUT)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(chunk))
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("QQ 图片分片上传被中断", e);
        } catch (IOException e) {
            throw new IllegalStateException("QQ 图片分片上传失败：" + e.getMessage(), e);
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("QQ 图片分片上传返回 HTTP " + resp.statusCode());
        }
    }

    private static String digestHex(String algorithm, byte[] data, int off, int len) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] digest = md.digest(len == data.length ? data : Arrays.copyOfRange(data, off, off + len));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("摘要算法不可用：" + algorithm, e);
        }
    }

    private void send(String path, String token, String body) {
        JsonNode resp = postJson(API_BASE + path, body, token);
        int code = resp.path("code").asInt(0);
        if (code != 0) {
            throw new IllegalStateException("QQ 发消息失败（" + code + "）：" + resp.path("message").asText(""));
        }
    }

    private JsonNode postJson(String url, String body, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(API_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (token != null) {
            builder.header("Authorization", "QQBot " + token);
        }
        try {
            HttpResponse<String> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String payload = resp.body() == null || resp.body().isBlank() ? "{}" : resp.body();
            // upload_part_finish 等接口成功时无 body，只能靠 HTTP 状态判定成败
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IllegalStateException("QQ 接口返回 HTTP " + resp.statusCode()
                        + "：" + truncate(payload, 300));
            }
            return Json.mapper().readTree(payload);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("QQ 接口请求被中断", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("QQ 接口请求失败：" + e.getMessage(), e);
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
