package com.guyu.agentteam.entity;

import com.guyu.agentteam.config.DpapiStringConverter;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "model_presets")
public class ModelPreset {

    @Id
    private String id;
    private String name;
    /** 协议类型：openai-chat（对话）/ dashscope-image、openai-image（文生图） */
    private String protocol;
    private String baseUrl;
    @Convert(converter = DpapiStringConverter.class)
    private String apiKey;
    private String remark;
    /** 推理强度（reasoning_effort）：空串不发送该参数；部分供应商默认不返回思考内容，需显式 low/medium/high 开启 */
    private String reasoningEffort;
    private Long createdAt;
    private Long updatedAt;
}
