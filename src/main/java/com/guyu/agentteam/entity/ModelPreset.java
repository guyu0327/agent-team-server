package com.guyu.agentteam.entity;

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
    private String baseUrl;
    private String apiKey;
    private String remark;
    private Long createdAt;
    private Long updatedAt;
}
