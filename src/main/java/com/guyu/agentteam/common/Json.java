package com.guyu.agentteam.common;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** 消息附件 JSON 与对象互转 */
public final class Json {

    public record Attachment(String path, String type, String name) {
    }

    private static final TypeReference<List<Attachment>> ATTACHMENT_LIST = new TypeReference<>() {
    };

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
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
