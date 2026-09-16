package com.guyu.agentteam.service;

import com.guyu.agentteam.common.ApiException;
import com.guyu.agentteam.common.SecretCipher;
import com.guyu.agentteam.dto.AsrStreamStatusDto;
import com.guyu.agentteam.dto.XfyunAsrConfigDto;
import com.guyu.agentteam.entity.AppSetting;
import com.guyu.agentteam.repository.AppSettingRepository;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 讯飞流式听写配置：持久化于 app_settings（key=asr.streamConfig），其中 apiKey/apiSecret
 * 经 DPAPI 加密存储。API 层永不回传密钥明文，仅返回 {@link AsrStreamStatusDto} 状态；
 * 保存时密钥留空表示保持不变，三项全部留空表示清除配置。
 * 另负责生成带鉴权参数的 WebSocket 连接地址（鉴权算法见讯飞语音听写流式 WebAPI 文档）。
 */
@Service
public class AsrStreamService {

    private static final String KEY_CONFIG = "asr.streamConfig";
    private static final String WS_HOST = "iat-api.xfyun.cn";
    private static final String WS_PATH = "/v2/iat";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AppSettingRepository settings;

    public AsrStreamService(AppSettingRepository settings) {
        this.settings = settings;
    }

    public XfyunAsrConfigDto getConfig() {
        String json = readSetting();
        if (json == null) {
            return new XfyunAsrConfigDto("", "", "");
        }
        try {
            XfyunAsrConfigDto cfg = MAPPER.readValue(json, XfyunAsrConfigDto.class);
            return new XfyunAsrConfigDto(
                    cfg.appId(), SecretCipher.decrypt(cfg.apiKey()), SecretCipher.decrypt(cfg.apiSecret()));
        } catch (Exception e) {
            return new XfyunAsrConfigDto("", "", "");
        }
    }

    /**
     * 保存配置（密钥留空 = 沿用现有值）：appId 为空时必须三项全空（清除配置）；
     * appId 非空但现有密钥缺失且未提供时拒绝。
     */
    public AsrStreamStatusDto saveConfig(XfyunAsrConfigDto req) {
        String appId = trimOrNull(req.appId());
        String apiKey = trimOrNull(req.apiKey());
        String apiSecret = trimOrNull(req.apiSecret());
        if (appId == null && (apiKey != null || apiSecret != null)) {
            throw ApiException.badRequest("appId 不能为空；全部留空则清除实时识别配置");
        }
        if (appId == null) {
            saveSetting("");
            return status(new XfyunAsrConfigDto("", "", ""));
        }
        XfyunAsrConfigDto current = getConfig();
        String key = apiKey != null ? apiKey : current.apiKey();
        String secret = apiSecret != null ? apiSecret : current.apiSecret();
        if (key.isBlank() || secret.isBlank()) {
            throw ApiException.badRequest("apiKey、apiSecret 需填写完整（已配置过的可留空表示保持不变）");
        }
        XfyunAsrConfigDto cfg = new XfyunAsrConfigDto(appId, key, secret);
        XfyunAsrConfigDto stored = new XfyunAsrConfigDto(
                cfg.appId(), SecretCipher.encrypt(cfg.apiKey()), SecretCipher.encrypt(cfg.apiSecret()));
        saveSetting(MAPPER.writeValueAsString(stored));
        return status(cfg);
    }

    public AsrStreamStatusDto status() {
        return status(getConfig());
    }

    private AsrStreamStatusDto status(XfyunAsrConfigDto cfg) {
        return new AsrStreamStatusDto(cfg.appId(), !isBlank(cfg.apiKey()), !isBlank(cfg.apiSecret()),
                !isBlank(cfg.appId()) && !isBlank(cfg.apiKey()) && !isBlank(cfg.apiSecret()));
    }

    public boolean isConfigured() {
        XfyunAsrConfigDto cfg = getConfig();
        return !isBlank(cfg.appId()) && !isBlank(cfg.apiKey()) && !isBlank(cfg.apiSecret());
    }

    /** 每次连接即时生成鉴权 URL；date 与讯飞服务器差需在 300s 内，本机时钟漂移过大时对端返回 401 */
    public URI buildWsUrl(XfyunAsrConfigDto cfg) {
        try {
            String date = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.RFC_1123_DATE_TIME);
            String origin = "host: " + WS_HOST + "\ndate: " + date + "\nGET " + WS_PATH + " HTTP/1.1";
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(cfg.apiSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = Base64.getEncoder().encodeToString(mac.doFinal(origin.getBytes(StandardCharsets.UTF_8)));
            String authorizationOrigin = "api_key=\"" + cfg.apiKey()
                    + "\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\""
                    + signature + "\"";
            String authorization = Base64.getEncoder()
                    .encodeToString(authorizationOrigin.getBytes(StandardCharsets.UTF_8));
            String query = "authorization=" + URLEncoder.encode(authorization, StandardCharsets.UTF_8)
                    + "&date=" + URLEncoder.encode(date, StandardCharsets.UTF_8)
                    + "&host=" + URLEncoder.encode(WS_HOST, StandardCharsets.UTF_8);
            return new URI("wss://" + WS_HOST + WS_PATH + "?" + query);
        } catch (Exception e) {
            throw ApiException.badRequest("生成转写连接失败：" + e.getMessage());
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** trim 后为空则返回 null（null 表示「未提供」，区别于空串） */
    private String trimOrNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    private String readSetting() {
        return settings.findById(KEY_CONFIG).map(AppSetting::getSettingValue)
                .filter(v -> v != null && !v.isBlank())
                .orElse(null);
    }

    private void saveSetting(String value) {
        AppSetting s = settings.findById(KEY_CONFIG).orElseGet(() -> {
            AppSetting n = new AppSetting();
            n.setSettingKey(KEY_CONFIG);
            return n;
        });
        s.setSettingValue(value);
        s.setUpdatedAt(System.currentTimeMillis());
        settings.save(s);
    }
}
