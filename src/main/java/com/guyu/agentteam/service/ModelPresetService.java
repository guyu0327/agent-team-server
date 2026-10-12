package com.guyu.agentteam.service;

import com.guyu.agentteam.common.Str;
import com.guyu.agentteam.common.ApiException;
import com.guyu.agentteam.common.Ids;
import com.guyu.agentteam.dto.ModelPresetDto;
import com.guyu.agentteam.dto.ModelPresetTestRequest;
import com.guyu.agentteam.dto.ModelPresetTestResult;
import com.guyu.agentteam.dto.ModelPresetUpsertRequest;
import com.guyu.agentteam.dto.PresetApplyAgentsRequest;
import com.guyu.agentteam.dto.PresetApplyResult;
import com.guyu.agentteam.entity.Agent;
import com.guyu.agentteam.entity.AppLog;
import com.guyu.agentteam.entity.ModelPreset;
import com.guyu.agentteam.repository.AgentRepository;
import com.guyu.agentteam.repository.ModelPresetRepository;
import com.guyu.agentteam.service.orchestration.AgentModelFactory;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
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

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int TEST_REPLY_MAX = 200;

    private final ModelPresetRepository presets;
    private final AgentRepository agents;
    private final AgentModelFactory modelFactory;
    private final AppLogService appLogs;

    public ModelPresetService(ModelPresetRepository presets, AgentRepository agents,
                              AgentModelFactory modelFactory, AppLogService appLogs) {
        this.presets = presets;
        this.agents = agents;
        this.modelFactory = modelFactory;
        this.appLogs = appLogs;
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

    /**
     * 用表单当前值（可未保存）发一条真实消息验证连通性，仅支持对话预设；
     * apiKey 留空且 presetId 非空时回落到库内已存密钥（编辑态「保持不变」场景）。
     */
    public ModelPresetTestResult test(ModelPresetTestRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw ApiException.badRequest("请先填写模型名称");
        }
        if (req.baseUrl() == null || req.baseUrl().isBlank()) {
            throw ApiException.badRequest("请先填写 API 地址");
        }
        String protocol = Str.isBlank(req.protocol()) ? PROTOCOL_CHAT : req.protocol().trim();
        if (!PROTOCOL_CHAT.equals(protocol)) {
            throw ApiException.badRequest("该预设类型暂不支持测试连接");
        }
        String apiKey = req.apiKey() == null ? "" : req.apiKey().trim();
        if (apiKey.isEmpty() && !Str.isBlank(req.presetId())) {
            ModelPreset stored = presets.findById(req.presetId().trim()).orElse(null);
            if (stored != null && stored.getApiKey() != null) {
                apiKey = stored.getApiKey();
            }
        }
        ModelPreset probe = new ModelPreset();
        probe.setName(req.name().trim());
        probe.setProtocol(PROTOCOL_CHAT);
        probe.setBaseUrl(req.baseUrl().trim());
        probe.setApiKey(apiKey);
        long start = System.currentTimeMillis();
        try (ReActAgent tester = ReActAgent.builder()
                .name("preset-tester")
                .sysPrompt("你是模型连通性测试器。收到任何消息都只回复一个词：pong")
                .model(modelFactory.create(new Agent(), probe))
                .modelExecutionConfig(ConversationStreamSupport.modelCallExecutionConfig())
                .build()) {
            Msg out = tester.call(List.of(UserMessage.builder().textContent("ping").build())).block(TEST_TIMEOUT);
            long latency = System.currentTimeMillis() - start;
            String reply = out == null || out.getTextContent() == null ? "" : out.getTextContent().trim();
            if (reply.length() > TEST_REPLY_MAX) {
                reply = reply.substring(0, TEST_REPLY_MAX) + "…";
            }
            if (reply.isEmpty()) {
                return new ModelPresetTestResult(false, latency, "",
                        "模型已连通但未返回内容，请确认模型名称是否正确");
            }
            return new ModelPresetTestResult(true, latency, reply, "连接成功");
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            appLogs.record(AppLog.TYPE_API_ERROR, "", "",
                    "模型预设「" + req.name().trim() + "」测试连接失败：" + ConversationStreamSupport.errorDetail(e));
            return new ModelPresetTestResult(false, latency, "", "连接失败：" + rootMessage(e));
        }
    }

    /** 批量应用到智能体：对话预设写 presetId，文生图预设写 imagePresetId；任一智能体不存在则整体不生效 */
    @Transactional
    public PresetApplyResult applyToAgents(String presetId, PresetApplyAgentsRequest req) {
        ModelPreset p = find(presetId);
        if (req == null || req.agentIds() == null || req.agentIds().isEmpty()) {
            throw ApiException.badRequest("请选择要应用的智能体");
        }
        boolean chat = PROTOCOL_CHAT.equals(p.getProtocol());
        List<Agent> targets = req.agentIds().stream()
                .map(id -> id == null ? "" : id.trim())
                .filter(id -> !id.isEmpty())
                .distinct()
                .map(id -> agents.findById(id).orElseThrow(() -> ApiException.notFound("智能体不存在")))
                .toList();
        if (targets.isEmpty()) {
            throw ApiException.badRequest("请选择要应用的智能体");
        }
        long now = System.currentTimeMillis();
        for (Agent a : targets) {
            if (chat) {
                a.setPresetId(presetId);
            } else {
                a.setImagePresetId(presetId);
            }
            a.setUpdatedAt(now);
            agents.save(a);
        }
        return new PresetApplyResult(targets.size());
    }

    /** 取最深一层的异常消息给界面展示（完整异常链记运行日志） */
    private static String rootMessage(Throwable e) {
        String msg = e.getMessage();
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
            if (t.getMessage() != null && !t.getMessage().isBlank()) {
                msg = t.getMessage();
            }
        }
        String text = msg == null ? t.getClass().getSimpleName() : msg.trim();
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
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
        if (!Str.isBlank(req.protocol()) && !SUPPORTED_PROTOCOLS.contains(req.protocol().trim())) {
            throw ApiException.badRequest("不支持的预设类型：" + req.protocol());
        }
    }


    private void apply(ModelPreset p, ModelPresetUpsertRequest req, boolean isCreate) {
        p.setName(req.name().trim());
        p.setProtocol(Str.isBlank(req.protocol()) ? PROTOCOL_CHAT : req.protocol().trim());
        p.setBaseUrl(req.baseUrl().trim());
        p.setRemark(req.remark() == null ? "" : req.remark().trim());
        if (req.apiKey() != null && !req.apiKey().isBlank()) {
            p.setApiKey(req.apiKey().trim());
        } else if (isCreate) {
            p.setApiKey("");
        }
    }
}
