package com.guyu.agentteam.service.qq;

import com.guyu.agentteam.common.ApiException;
import com.guyu.agentteam.common.Json;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * QQ 官方「扫码绑定机器人」流程（协议对照 @tencent-connect/qqbot-connector SDK）：
 * q.qq.com/lite/create_bind_task 建任务（上送随机 32 字节 key 作对称密钥）→
 * 二维码内容为 connect.html 链接，手机 QQ 扫码选择机器人并确认 →
 * poll_bind_result 每 2 秒轮询（status 2=完成 3=过期），完成的 bot_encrypt_secret
 * 为 AES-256-GCM 密文（结构 iv12 + ciphertext + tag16，密钥即建任务的 key）。
 */
@Component
public class QqQrService {

    public record Start(String taskId, String qrUrl) {
    }

    /** pending 等待扫码 / completed 携带凭据与扫码人 openid（机主）/ expired 需重建任务换新码 */
    public record Poll(String status, String appId, String appSecret, String userOpenid) {
    }

    private record Session(byte[] key, long createdAt) {
    }

    private static final String HOST = "q.qq.com";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    /** 绑定任务平台侧几分钟即过期，本地会话超时后直接作废（前端会收到 expired 并换新码） */
    private static final long SESSION_TTL_MS = 10 * 60_000L;
    private static final int STATUS_PENDING = 1;
    private static final int STATUS_COMPLETED = 2;
    private static final int STATUS_EXPIRED = 3;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    /** 创建绑定任务：返回二维码内容链接（前端渲染成图片供手机 QQ 扫码） */
    public Start start() {
        purgeExpired();
        byte[] key = new byte[32];
        random.nextBytes(key);
        String keyB64 = Base64.getEncoder().encodeToString(key);
        JsonNode resp = post(HOST + "/lite/create_bind_task", Map.of("key", keyB64));
        if (resp.path("retcode").asInt(-1) != 0) {
            throw ApiException.badRequest("创建扫码绑定任务失败：" + resp.path("msg").asText("未知错误"));
        }
        String taskId = resp.path("data").path("task_id").asText("");
        if (taskId.isEmpty()) {
            throw ApiException.badRequest("创建扫码绑定任务失败：平台未返回任务 id");
        }
        sessions.put(taskId, new Session(key, System.currentTimeMillis()));
        return new Start(taskId, connectUrl(taskId));
    }

    /** 轮询一次绑定结果；任务不存在或已超时按 expired 返回，前端换新码重来 */
    public Poll poll(String taskId) {
        Session session = sessions.get(taskId);
        if (session == null || System.currentTimeMillis() - session.createdAt() > SESSION_TTL_MS) {
            sessions.remove(taskId);
            return new Poll("expired", "", "", "");
        }
        JsonNode resp = post(HOST + "/lite/poll_bind_result", Map.of("task_id", taskId));
        if (resp.path("retcode").asInt(-1) != 0) {
            throw ApiException.badRequest("查询扫码绑定结果失败：" + resp.path("msg").asText("未知错误"));
        }
        JsonNode data = resp.path("data");
        int st = data.path("status").asInt(0);
        if (st == STATUS_COMPLETED) {
            sessions.remove(taskId);
            String appId = data.path("bot_appid").asText("");
            String secret;
            try {
                secret = decryptSecret(data.path("bot_encrypt_secret").asText(""), session.key());
            } catch (Exception e) {
                throw ApiException.badRequest("扫码成功但凭据解密失败：" + e.getMessage());
            }
            return new Poll("completed", appId, secret, data.path("user_openid").asText(""));
        }
        if (st == STATUS_EXPIRED) {
            sessions.remove(taskId);
            return new Poll("expired", "", "", "");
        }
        return new Poll("pending", "", "", "");
    }

    private String connectUrl(String taskId) {
        return "https://" + HOST + "/qqbot/openclaw/connect.html?task_id=" + taskId + "&source=&_wv=2";
    }

    /** AES-256-GCM：密文 = base64(iv12 + ciphertext + tag16)，密钥为建任务时的 32 字节 key */
    private static String decryptSecret(String encrypted, byte[] key) throws Exception {
        byte[] all = Base64.getDecoder().decode(encrypted);
        if (all.length <= 12 + 16) {
            throw new IllegalStateException("密文长度异常");
        }
        byte[] iv = new byte[12];
        System.arraycopy(all, 0, iv, 0, 12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Session>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().createdAt() > SESSION_TTL_MS) {
                it.remove();
            }
        }
    }

    private JsonNode post(String path, Map<String, String> body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://" + path))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.mapper().writeValueAsString(body),
                        StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            String payload = resp.body() == null || resp.body().isBlank() ? "{}" : resp.body();
            return Json.mapper().readTree(payload);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("QQ 扫码绑定请求被中断", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("QQ 扫码绑定请求失败：" + e.getMessage(), e);
        }
    }
}
