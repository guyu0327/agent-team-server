package com.guyu.agentteam.service.wechat;

import com.guyu.agentteam.common.Json;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;

/**
 * 微信 iLink Bot API 客户端（协议对照 Tencent/openclaw-weixin 官方实现）：
 * 扫码登录（get_bot_qrcode / get_qrcode_status 长轮询）、收消息（getupdates 长轮询）、
 * 发消息（sendmessage，须回传消息携带的 context_token）、启停通知。
 * 全部为 HTTPS + JSON，鉴权仅靠登录颁发的 bot_token。
 */
@Component
public class WeixinApiClient {

    /** 免鉴权固定入口：仅二维码相关请求走这里，消息接口用登录返回的 baseurl */
    public static final String FIXED_BASE_URL = "https://ilinkai.weixin.qq.com";
    /** get_bot_qrcode 的 bot_type（官方通道构建取值） */
    private static final String BOT_TYPE = "3";
    private static final String APP_ID = "bot";
    /** 镜像官方 2.4.9 的 client version 编码（major<<16|minor<<8|patch），纯观察用途 */
    private static final String CLIENT_VERSION = String.valueOf((2 << 16) | (4 << 8) | 9);
    private static final String CHANNEL_VERSION = "agentteam-1.0.0";
    private static final String BOT_AGENT = "AgentTeam/1.0";

    /** 普通请求超时 */
    private static final Duration API_TIMEOUT = Duration.ofSeconds(15);
    /** 长轮询客户端超时（服务端最长挂 35s） */
    public static final Duration LONG_POLL_TIMEOUT = Duration.ofSeconds(40);

    private static final Random RANDOM = new SecureRandom();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // ---------- 结果结构 ----------

    public record QrCode(String qrcode, String qrContent) {
    }

    /** 扫码状态：wait/scaned/need_verifycode/expired/scaned_but_redirect/binded_redirect/confirmed */
    public record QrStatus(String status, String botToken, String botId, String baseUrl,
                           String ownerUserId, String redirectHost) {
    }

    /** 媒体 item 类型（协议 MessageItemType） */
    public static final int MEDIA_IMAGE = 2;
    public static final int MEDIA_VOICE = 3;
    public static final int MEDIA_FILE = 4;
    public static final int MEDIA_VIDEO = 5;

    /** 媒体 CDN 引用（协议 CDNMedia）：full_url 优先，否则用 encrypt_query_param 拼默认 CDN 下载地址 */
    public record MediaRef(String encryptQueryParam, String aesKey, String fullUrl) {
    }

    /** 消息携带的媒体内容（图片/语音/文件/视频 item 的提取结果；语音可能带微信侧转写文本） */
    public record InboundMedia(int type, String fileName, String voiceText, MediaRef media,
                               String imageHexKey) {
    }

    /** 一条收到的消息（文本可能与其他类型 item 混排；媒体内容另见 media） */
    public record InboundMessage(String messageId, String fromUserId, String contextToken,
                                 String text, int itemType, InboundMedia media) {
    }

    public record Updates(List<InboundMessage> messages, String buf, int errcode, String errmsg) {
    }

    // ---------- 登录 ----------

    /** 获取登录二维码（免鉴权）。qrContent 为二维码内容 URL，展示端自行渲染 */
    public QrCode getBotQrcode() {
        JsonNode resp = post(FIXED_BASE_URL, "ilink/bot/get_bot_qrcode?bot_type=" + BOT_TYPE,
                "{\"local_token_list\":[]}", null, API_TIMEOUT);
        return new QrCode(resp.path("qrcode").asText(""), resp.path("qrcode_img_content").asText(""));
    }

    /** 长轮询扫码状态；网络/网关超时按 wait 处理由调用方重试 */
    public QrStatus getQrcodeStatus(String host, String qrcode, String verifyCode) {
        String ep = "ilink/bot/get_qrcode_status?qrcode=" + urlEncode(qrcode)
                + (verifyCode == null || verifyCode.isBlank() ? "" : "&verify_code=" + urlEncode(verifyCode));
        try {
            JsonNode resp = get(host, ep);
            return new QrStatus(resp.path("status").asText(""), resp.path("bot_token").asText(null),
                    resp.path("ilink_bot_id").asText(null), resp.path("baseurl").asText(null),
                    resp.path("ilink_user_id").asText(null), resp.path("redirect_host").asText(null));
        } catch (Exception e) {
            return new QrStatus("wait", null, null, null, null, null);
        }
    }

    // ---------- 消息 ----------

    /** 长轮询收消息；返回 errcode -14 表示会话超时需重新扫码，网络异常向上抛由轮询循环兜底重试 */
    public Updates getUpdates(String baseUrl, String token, String buf) {
        String body = Json.mapper().writeValueAsString(java.util.Map.of(
                "get_updates_buf", buf == null ? "" : buf,
                "base_info", baseInfo()));
        JsonNode resp = post(baseUrl, "ilink/bot/getupdates", body, token, LONG_POLL_TIMEOUT);
        List<InboundMessage> out = new ArrayList<>();
        for (JsonNode m : resp.path("msgs")) {
            if (m.path("message_type").asInt(0) != 1) {
                continue; // 只处理用户消息，机器人自己发的（message_type=2）跳过防回环
            }
            int itemType = 0;
            InboundMedia media = null;
            StringBuilder text = new StringBuilder();
            for (JsonNode item : m.path("item_list")) {
                int t = item.path("type").asInt(0);
                if (itemType == 0) {
                    itemType = t;
                }
                if (t == 1) {
                    text.append(item.path("text_item").path("text").asText(""));
                } else if (media == null) {
                    media = parseMedia(item, t);
                }
            }
            out.add(new InboundMessage(m.path("message_id").asText(""),
                    m.path("from_user_id").asText(""), m.path("context_token").asText(null),
                    text.toString(), itemType, media));
        }
        return new Updates(out, resp.path("get_updates_buf").asText(buf == null ? "" : buf),
                resp.path("errcode").asInt(0), resp.path("errmsg").asText(null));
    }

    /** 提取媒体 item 的下载信息；无 CDN 下载参数时返回 null（按不支持处理） */
    private static InboundMedia parseMedia(JsonNode item, int type) {
        if (type < MEDIA_IMAGE || type > MEDIA_VIDEO) {
            return null;
        }
        JsonNode mi = item.path(switch (type) {
            case MEDIA_IMAGE -> "image_item";
            case MEDIA_VOICE -> "voice_item";
            case MEDIA_FILE -> "file_item";
            default -> "video_item";
        });
        JsonNode ref = mi.path("media");
        String eqp = ref.path("encrypt_query_param").asText("");
        String fullUrl = ref.path("full_url").asText("");
        if (eqp.isBlank() && fullUrl.isBlank()) {
            return null;
        }
        return new InboundMedia(type,
                type == MEDIA_FILE ? mi.path("file_name").asText(null) : null,
                type == MEDIA_VOICE ? mi.path("text").asText(null) : null,
                new MediaRef(eqp, ref.path("aes_key").asText(null), fullUrl),
                type == MEDIA_IMAGE ? mi.path("aeskey").asText(null) : null);
    }

    /** 发文本消息；必须回传收到消息时携带的 context_token，否则视为无上下文的新会话消息 */
    public void sendText(String baseUrl, String token, String toUserId, String contextToken, String text) {
        var msg = new java.util.LinkedHashMap<String, Object>();
        msg.put("from_user_id", "");
        msg.put("to_user_id", toUserId);
        msg.put("client_id", "agentteam-" + System.currentTimeMillis());
        msg.put("message_type", 2);
        msg.put("message_state", 2);
        msg.put("item_list", List.of(java.util.Map.of("type", 1, "text_item", java.util.Map.of("text", text))));
        if (contextToken != null && !contextToken.isBlank()) {
            msg.put("context_token", contextToken);
        }
        String body = Json.mapper().writeValueAsString(java.util.Map.of("msg", msg, "base_info", baseInfo()));
        JsonNode resp = post(baseUrl, "ilink/bot/sendmessage", body, token, API_TIMEOUT);
        int ret = resp.path("ret").asInt(0);
        if (ret != 0) {
            throw new IllegalStateException("sendmessage ret=" + ret + " errmsg=" + resp.path("errmsg").asText(""));
        }
    }

    /** 通道启停通知（官方礼节：上线 notifystart、下线 notifystop），失败不影响主流程 */
    public void notify(boolean start, String baseUrl, String token) {
        try {
            post(baseUrl, "ilink/bot/msg/notifyst" + (start ? "art" : "op"),
                    Json.mapper().writeValueAsString(java.util.Map.of("base_info", baseInfo())),
                    token, Duration.ofSeconds(10));
        } catch (Exception ignored) {
        }
    }

    // ---------- HTTP 基础 ----------

    private java.util.Map<String, String> commonHeaders() {
        return java.util.Map.of(
                "iLink-App-Id", APP_ID,
                "iLink-App-ClientVersion", CLIENT_VERSION);
    }

    private java.util.Map<String, String> authHeaders(String token) {
        java.util.Map<String, String> h = new java.util.LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        h.put("AuthorizationType", "ilink_bot_token");
        // X-WECHAT-UIN：随机 uint32 十进制字符串的 Base64（协议要求，值无语义）
        h.put("X-WECHAT-UIN", Base64.getEncoder()
                .encodeToString(String.valueOf(Math.abs(RANDOM.nextLong() & 0xffffffffL))
                        .getBytes(StandardCharsets.UTF_8)));
        h.putAll(commonHeaders());
        if (token != null && !token.isBlank()) {
            h.put("Authorization", "Bearer " + token);
        }
        return h;
    }

    private java.util.Map<String, Object> baseInfo() {
        return java.util.Map.of("channel_version", CHANNEL_VERSION, "bot_agent", BOT_AGENT);
    }

    private JsonNode post(String baseUrl, String endpoint, String body, String token, Duration timeout) {
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl.endsWith("/") ? baseUrl + endpoint : baseUrl + "/" + endpoint))
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            authHeaders(token).forEach(rb::header);
            HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("iLink " + endpoint + " HTTP " + resp.statusCode()
                        + ": " + snippet(resp.body()));
            }
            return Json.mapper().readTree(resp.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("iLink " + endpoint + " 请求失败：" + e.getMessage(), e);
        }
    }

    private JsonNode get(String host, String endpoint) throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create("https://" + host + "/" + endpoint))
                .timeout(LONG_POLL_TIMEOUT)
                .GET();
        commonHeaders().forEach(rb::header);
        HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("iLink " + endpoint + " HTTP " + resp.statusCode());
        }
        return Json.mapper().readTree(resp.body());
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String snippet(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
