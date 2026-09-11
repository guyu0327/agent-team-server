package com.guyu.agentteam.controller;

import com.guyu.agentteam.common.ApiException;
import com.guyu.agentteam.entity.Agent;
import com.guyu.agentteam.entity.ModelPreset;
import com.guyu.agentteam.repository.AgentRepository;
import com.guyu.agentteam.repository.ModelPresetRepository;
import com.guyu.agentteam.service.orchestration.AgentModelFactory;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** 阶段1 冒烟：验证 AgentScope 能用现有模型预设驱动 ReActAgent */
@RestController
@RequestMapping("/api/debug")
public class DebugController {

    private final AgentRepository agents;
    private final ModelPresetRepository presets;
    private final AgentModelFactory modelFactory;

    public DebugController(AgentRepository agents, ModelPresetRepository presets, AgentModelFactory modelFactory) {
        this.agents = agents;
        this.presets = presets;
        this.modelFactory = modelFactory;
    }

    @GetMapping("/agent-smoke")
    public Map<String, Object> agentSmoke(
            @RequestParam String agentId,
            @RequestParam(defaultValue = "你好，请用一句话介绍你自己") String q) {
        Agent agent = agents.findById(agentId)
                .orElseThrow(() -> new ApiException(404, "智能体不存在"));
        if (agent.getPresetId() == null || agent.getPresetId().isBlank()) {
            throw new ApiException(400, "该智能体未关联模型预设");
        }
        ModelPreset preset = presets.findById(agent.getPresetId())
                .orElseThrow(() -> new ApiException(400, "模型预设不存在"));

        StringBuilder text = new StringBuilder();
        AtomicReference<Msg> result = new AtomicReference<>();
        long start = System.currentTimeMillis();
        try (ReActAgent scope = ReActAgent.builder()
                .name(agent.getName())
                .sysPrompt(agent.getSystemPrompt() == null ? "" : agent.getSystemPrompt())
                .model(modelFactory.create(agent, preset))
                .build()) {
            scope.streamEvents(new UserMessage(q))
                    .doOnNext(e -> {
                        if (e.getType() == AgentEventType.TEXT_BLOCK_DELTA) {
                            text.append(((TextBlockDeltaEvent) e).getDelta());
                        } else if (e.getType() == AgentEventType.AGENT_RESULT) {
                            result.set(((AgentResultEvent) e).getResult());
                        }
                    })
                    .blockLast(Duration.ofSeconds(90));
        }
        long ms = System.currentTimeMillis() - start;

        String reply = result.get() != null ? result.get().getTextContent() : text.toString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agent", agent.getName());
        body.put("model", preset.getName() + " @ " + preset.getBaseUrl());
        body.put("reply", reply == null || reply.isBlank() ? "（模型未返回内容）" : reply);
        body.put("ms", ms);
        return body;
    }
}
