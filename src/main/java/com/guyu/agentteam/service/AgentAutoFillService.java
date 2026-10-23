package com.guyu.agentteam.service;

import com.guyu.agentteam.common.ApiException;
import com.guyu.agentteam.common.Str;
import com.guyu.agentteam.dto.AgentAutoFillDto;
import com.guyu.agentteam.dto.AgentAutoFillRequest;
import com.guyu.agentteam.entity.Agent;
import com.guyu.agentteam.entity.AppLog;
import com.guyu.agentteam.entity.ModelPreset;
import com.guyu.agentteam.repository.ModelPresetRepository;
import com.guyu.agentteam.service.orchestration.AgentModelFactory;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 一句话自动生成智能体配置：用用户指定的对话模型做单轮结构化输出，
 * 只生成基础六项（名称/头像/分组/简介/系统提示词/温度）；模型预设与技能不参与生成。
 */
@Service
public class AgentAutoFillService {

    private static final Duration GENERATE_TIMEOUT = Duration.ofSeconds(120);

    private static final String SYSTEM_PROMPT = """
            你是「智能体配置生成器」。用户会用一句话描述想要的智能体，你据此生成一套可直接使用的配置。

            要求：
            1. name：简短好记的名字，2~8 个字。
            2. avatar：选一个贴合职能的单个 emoji 作头像。
            3. groupName：所属分组，2~6 个字（如「办公助手」「创作」「开发」「生活」），归入最贴切的常见分类。
            4. description：一句话说明这个智能体能帮用户做什么，20~60 字。
            5. systemPrompt：完整可用的系统提示词，250~800 字，用中文书写；涵盖角色定位、能力边界、\
            工作方式、输出风格与要求，语句具体可执行，直接写给智能体本人看，不要写成对用户的说明。
            6. temperature：0~2 之间、保留一位小数；创意/写作类取 0.8~1.2，事实问答与工具执行类取 0.3~0.7。
            把结果填入对应字段返回，不要输出其他内容。
            """;

    /** 结构化输出载体（与模型约定的字段） */
    public record AgentDraftOut(String name, String avatar, String groupName, String description,
                                String systemPrompt, Double temperature) {
    }

    private final ModelPresetRepository presets;
    private final AgentModelFactory modelFactory;
    private final AppLogService appLogs;

    public AgentAutoFillService(ModelPresetRepository presets, AgentModelFactory modelFactory, AppLogService appLogs) {
        this.presets = presets;
        this.modelFactory = modelFactory;
        this.appLogs = appLogs;
    }

    public AgentAutoFillDto generate(AgentAutoFillRequest req) {
        if (req == null || Str.isBlank(req.prompt())) {
            throw ApiException.badRequest("请先用一句话描述你想要的智能体");
        }
        if (Str.isBlank(req.presetId())) {
            throw ApiException.badRequest("请选择用于生成的模型");
        }
        ModelPreset preset = presets.findById(req.presetId().trim())
                .orElseThrow(() -> ApiException.badRequest("模型预设不存在"));
        if (!ModelPresetService.PROTOCOL_CHAT.equals(preset.getProtocol())) {
            throw ApiException.badRequest("生成需要对话类模型，请选择对话预设");
        }
        String userText = "我想要的智能体：" + req.prompt().trim();
        try (ReActAgent generator = ReActAgent.builder()
                .name("agent-drafter")
                .sysPrompt(SYSTEM_PROMPT)
                .model(modelFactory.create(new Agent(), preset))
                .modelExecutionConfig(ConversationStreamSupport.modelCallExecutionConfig())
                .build()) {
            Msg out = generator.call(List.of(UserMessage.builder().textContent(userText).build()), AgentDraftOut.class)
                    .block(GENERATE_TIMEOUT);
            if (out == null || !out.hasStructuredData()) {
                throw ApiException.badRequest("模型未返回有效结果，请重试或换一个模型");
            }
            AgentDraftOut draft = out.getStructuredData(AgentDraftOut.class);
            return normalize(draft, req.prompt().trim());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            appLogs.record(AppLog.TYPE_ERROR, "", "",
                    "智能体自动生成失败：" + ConversationStreamSupport.errorDetail(e));
            throw ApiException.badRequest("生成失败：" + rootMessage(e));
        }
    }

    private AgentAutoFillDto normalize(AgentDraftOut draft, String prompt) {
        String name = draft.name() == null ? "" : draft.name().trim();
        if (name.isEmpty()) {
            name = "新智能体";
        }
        String avatar = draft.avatar() == null ? "" : draft.avatar().trim();
        if (!avatar.isEmpty()) {
            // 只保留第一个码点，防模型输出多个字符或解释文字
            avatar = new String(Character.toChars(avatar.codePointAt(0)));
        }
        String groupName = draft.groupName() == null ? "" : draft.groupName().trim();
        String description = draft.description() == null ? "" : draft.description().trim();
        String systemPrompt = draft.systemPrompt() == null ? "" : draft.systemPrompt().trim();
        Double t = draft.temperature();
        double temperature = t == null ? 0.7 : Math.max(0, Math.min(2, Math.round(t * 10) / 10.0));
        return new AgentAutoFillDto(name, avatar, groupName, description, systemPrompt, temperature);
    }

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
}
