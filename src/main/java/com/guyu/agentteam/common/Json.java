package com.guyu.agentteam.common;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** 消息附件 JSON 与对象互转 */
public final class Json {

    public record Attachment(String path, String type, String name, boolean consumed) {

        /** 新建附件默认未阅（consumed=false）：图片在被模型看过一轮后由上下文压缩服务标记为已阅 */
        public Attachment(String path, String type, String name) {
            this(path, type, name, false);
        }
    }

    private static final TypeReference<List<Attachment>> ATTACHMENT_LIST = new TypeReference<>() {
    };

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    /** 全项目共享的 Jackson 3 实例：所有 JSON 互转统一走它，避免多处自建实例行为漂移（Jackson 2 已全部迁出） */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String writeAttachments(List<Attachment> attachments) {
        return MAPPER.writeValueAsString(attachments);
    }

    /** 解析附件 JSON；空值或格式非法返回空列表 */
    public static List<Attachment> readAttachments(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Attachment> list = MAPPER.readValue(json, ATTACHMENT_LIST);
            return list == null ? List.of() : list;
        } catch (Exception e) {
            return List.of();
        }
    }
}
