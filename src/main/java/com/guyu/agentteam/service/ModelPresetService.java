package com.guyu.agentteam.service;

import com.guyu.agentteam.common.ApiException;
import com.guyu.agentteam.common.Ids;
import com.guyu.agentteam.dto.ModelPresetDto;
import com.guyu.agentteam.dto.ModelPresetUpsertRequest;
import com.guyu.agentteam.entity.ModelPreset;
import com.guyu.agentteam.repository.AgentRepository;
import com.guyu.agentteam.repository.ModelPresetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ModelPresetService {

    /** 协议类型常量：对话（OpenAI 兼容）/ DashScope / OpenAI Images / 硅基流动 文生图 */
    public static final String PROTOCOL_CHAT = "openai-chat";
    public static final String PROTOCOL_DASHSCOPE_IMAGE = "dashscope-image";
    public static final String PROTOCOL_OPENAI_IMAGE = "openai-image";
    public static final String PROTOCOL_SILICONFLOW_IMAGE = "siliconflow-image";

    public static final List<String> SUPPORTED_PROTOCOLS =
            List.of(PROTOCOL_CHAT, PROTOCOL_DASHSCOPE_IMAGE, PROTOCOL_OPENAI_IMAGE, PROTOCOL_SILICONFLOW_IMAGE);

    private final ModelPresetRepository presets;
    private final AgentRepository agents;

    public ModelPresetService(ModelPresetRepository presets, AgentRepository agents) {
        this.presets = presets;
        this.agents = agents;
    }

    @Transactional(readOnly = true)
    public List<ModelPresetDto> list() {
        return presets.findByOrderByCreatedAtAsc().stream().map(ModelPresetDto::from).toList();
    }

    @Transactional
    public ModelPresetDto create(ModelPresetUpsertRequest req) {
        validate(req);
        long now = System.currentTimeMillis();
        ModelPreset p = new ModelPreset();
        p.setId(Ids.next());
        apply(p, req, true);
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        presets.save(p);
        return ModelPresetDto.from(p);
    }

    @Transactional
    public ModelPresetDto update(String id, ModelPresetUpsertRequest req) {
        validate(req);
        ModelPreset p = find(id);
        apply(p, req, false);
        p.setUpdatedAt(System.currentTimeMillis());
        presets.save(p);
        return ModelPresetDto.from(p);
    }

    @Transactional
    public void delete(String id) {
        long used = agents.countByPresetId(id);
        long usedAsImage = agents.countByImagePresetId(id);
        if (used > 0 || usedAsImage > 0) {
            throw ApiException.badRequest("该预设正被 " + (used + usedAsImage) + " 个智能体使用，无法删除");
        }
        presets.delete(find(id));
    }

    private ModelPreset find(String id) {
        return presets.findById(id).orElseThrow(() -> ApiException.notFound("模型预设不存在"));
    }

    private void validate(ModelPresetUpsertRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw ApiException.badRequest("模型名称不能为空");
        }
        if (req.baseUrl() == null || req.baseUrl().isBlank()) {
            throw ApiException.badRequest("API 地址不能为空");
        }
        if (!isBlank(req.protocol()) && !SUPPORTED_PROTOCOLS.contains(req.protocol().trim())) {
            throw ApiException.badRequest("不支持的预设类型：" + req.protocol());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private void apply(ModelPreset p, ModelPresetUpsertRequest req, boolean isCreate) {
        p.setName(req.name().trim());
        p.setProtocol(isBlank(req.protocol()) ? PROTOCOL_CHAT : req.protocol().trim());
        p.setBaseUrl(req.baseUrl().trim());
        p.setRemark(req.remark() == null ? "" : req.remark().trim());
        if (req.apiKey() != null && !req.apiKey().isBlank()) {
            p.setApiKey(req.apiKey().trim());
        } else if (isCreate) {
            p.setApiKey("");
        }
    }
}
