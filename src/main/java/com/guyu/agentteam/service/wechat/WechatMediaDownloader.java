package com.guyu.agentteam.service.wechat;

import com.guyu.agentteam.common.Str;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 微信 CDN 媒体下载与解密（协议对照 Tencent/openclaw-weixin pic-decrypt）：
 * full_url 优先，否则拼默认 CDN 的 /download?encrypted_query_param=...；
 * AES-128-ECB/PKCS7 解密，密钥兼容 base64(16 字节原始) 与 base64(32 位十六进制) 两种编码，
 * 图片两类密钥都缺失时按明文处理。
 */
@Component
class WechatMediaDownloader {

    static final String DEFAULT_CDN = "https://novac2c.cdn.weixin.qq.com/c2c";
    private static final long MAX_BYTES = 50L * 1024 * 1024;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 下载并解密，返回明文字节 */
    byte[] fetch(WeixinApiClient.InboundMedia m) throws Exception {
        WeixinApiClient.MediaRef ref = m.media();
        String url = ref.fullUrl();
        if (Str.isBlank(url)) {
            url = DEFAULT_CDN + "/download?encrypted_query_param="
                    + URLEncoder.encode(ref.encryptQueryParam(), StandardCharsets.UTF_8);
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("CDN HTTP " + resp.statusCode());
        }
        byte[] data = resp.body();
        if (data.length == 0) {
            throw new IllegalStateException("CDN 返回空内容");
        }
        if (data.length > MAX_BYTES) {
            throw new IllegalStateException("媒体超过 50MB 上限");
        }
        byte[] key = resolveKey(m);
        if (key == null) {
            if (m.type() == WeixinApiClient.MEDIA_IMAGE) {
                return data;
            }
            throw new IllegalStateException("媒体缺少 AES 密钥");
        }
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        return cipher.doFinal(data);
    }

    /** 图片优先 image_item.aeskey（32 位十六进制），其余媒体用 media.aes_key（base64） */
    private static byte[] resolveKey(WeixinApiClient.InboundMedia m) {
        if (m.type() == WeixinApiClient.MEDIA_IMAGE && !Str.isBlank(m.imageHexKey())) {
            return HexFormat.of().parseHex(m.imageHexKey().trim());
        }
        String b64 = m.media().aesKey();
        if (Str.isBlank(b64)) {
            return null;
        }
        byte[] decoded = Base64.getDecoder().decode(b64.trim());
        if (decoded.length == 16) {
            return decoded;
        }
        if (decoded.length == 32) {
            return HexFormat.of().parseHex(new String(decoded, StandardCharsets.US_ASCII).trim());
        }
        throw new IllegalStateException("AES 密钥长度非法：" + decoded.length + " 字节");
    }
}
