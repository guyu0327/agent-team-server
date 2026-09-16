package com.guyu.agentteam.config;

import com.guyu.agentteam.common.SecretCipher;
import com.sun.jna.Platform;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 启动时把存量明文密钥统一加密：model_presets.api_key 与 app_settings(asr.streamConfig)
 * 中的 apiKey/apiSecret。以 "dpapi:" 前缀判幂等，重复启动无副作用。
 */
@Component
public class SecretMigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(SecretMigrationRunner.class);
    private static final String DPAPI_PREFIX = "dpapi:";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public SecretMigrationRunner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void migrate() {
        selfCheck();
        int presets = migratePresets();
        int asr = migrateAsrConfig();
        if (presets + asr > 0) {
            log.info("存量密钥加密迁移完成：model_presets {} 条，asr.streamConfig {} 条", presets, asr);
        }
    }

    /** DPAPI 不可用时所有密钥都读不出来，直接拒绝启动 */
    private void selfCheck() {
        if (!Platform.isWindows()) {
            return;
        }
        String sample = "agent-team-secret-self-check";
        if (!sample.equals(SecretCipher.decrypt(SecretCipher.encrypt(sample)))) {
            throw new IllegalStateException("DPAPI 加解密自检失败，拒绝启动");
        }
    }

    private int migratePresets() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, api_key FROM model_presets WHERE api_key IS NOT NULL AND api_key != ''");
        int count = 0;
        for (Map<String, Object> row : rows) {
            String value = String.valueOf(row.get("api_key"));
            if (value.startsWith(DPAPI_PREFIX)) {
                continue;
            }
            jdbc.update("UPDATE model_presets SET api_key = ? WHERE id = ?",
                    SecretCipher.encrypt(value), String.valueOf(row.get("id")));
            count++;
        }
        return count;
    }

    private int migrateAsrConfig() {
        List<String> values = jdbc.queryForList(
                "SELECT setting_value FROM app_settings WHERE setting_key = 'asr.streamConfig'", String.class);
        if (values.isEmpty() || values.get(0) == null || values.get(0).isBlank()) {
            return 0;
        }
        try {
            JsonNode root = MAPPER.readTree(values.get(0));
            if (!(root instanceof ObjectNode obj)) {
                return 0;
            }
            boolean changed = encryptField(obj, "apiKey") | encryptField(obj, "apiSecret");
            if (changed) {
                jdbc.update("UPDATE app_settings SET setting_value = ? WHERE setting_key = 'asr.streamConfig'",
                        MAPPER.writeValueAsString(obj));
                return 1;
            }
            return 0;
        } catch (Exception e) {
            log.warn("asr.streamConfig 不是有效 JSON，跳过密钥迁移", e);
            return 0;
        }
    }

    private boolean encryptField(ObjectNode obj, String field) {
        JsonNode node = obj.get(field);
        if (node == null || !node.isTextual()) {
            return false;
        }
        String value = node.asText();
        if (value.isEmpty() || value.startsWith(DPAPI_PREFIX)) {
            return false;
        }
        obj.put(field, SecretCipher.encrypt(value));
        return true;
    }
}
