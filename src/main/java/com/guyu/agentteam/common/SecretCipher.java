package com.guyu.agentteam.common;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Crypt32Util;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 密钥存储加解密：Windows 下经 DPAPI（用户级密钥）加密落库，库文件/备份拷到其他 Windows
 * 账户或机器后密钥不可解。加密值带 "dpapi:" 前缀；无前缀按存量明文或非 Windows 平台的
 * 原样值处理（非 Windows 上不做加密，行为与明文存储一致）。
 */
public final class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);
    private static final String PREFIX = "dpapi:";

    private SecretCipher() {
    }

    /** 空值直通；返回值可直接落库 */
    public static String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        if (!Platform.isWindows()) {
            return plain;
        }
        byte[] encrypted = Crypt32Util.cryptProtectData(plain.getBytes(StandardCharsets.UTF_8));
        return PREFIX + Base64.getEncoder().encodeToString(encrypted);
    }

    /** 无前缀原样返回（存量明文兼容）；解密失败按空串处理，交由上层按未配置提示重填 */
    public static String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        try {
            byte[] bytes = Crypt32Util.cryptUnprotectData(
                    Base64.getDecoder().decode(stored.substring(PREFIX.length())));
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("密钥解密失败（数据库可能来自其他 Windows 账户），按未配置处理", e);
            return "";
        }
    }
}
