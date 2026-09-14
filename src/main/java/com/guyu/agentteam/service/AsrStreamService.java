package com.guyu.agentteam.service;

import com.guyu.agentteam.common.ApiException;
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
 * 讯飞流式听写配置：持久化于 app_settings（key=asr.streamConfig）。
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
            return MAPPER.readValue(json, XfyunAsrConfigDto.class);
        } catch (Exception e) {
            return new XfyunAsrConfigDto("", "", "");
        }
    }

    public XfyunAsrConfigDto saveConfig(XfyunAsrConfigDto req) {
        boolean allBlank = isBlank(req.appId()) && isBlank(req.apiKey()) && isBlank(req.apiSecret());
        if (!allBlank && (isBlank(req.appId()) || isBlank(req.apiKey()) || isBlank(req.apiSecret()))) {
            throw ApiException.badRequest("appId、apiKey、apiSecret 需全部填写；全部留空则清除实时识别配置");
        }
        XfyunAsrConfigDto cfg = allBlank
                ? new XfyunAsrConfigDto("", "", "")
                : new XfyunAsrConfigDto(req.appId().trim(), req.apiKey().trim(), req.apiSecret().trim());
        saveSetting(MAPPER.writeValueAsString(cfg));
        return cfg;
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
