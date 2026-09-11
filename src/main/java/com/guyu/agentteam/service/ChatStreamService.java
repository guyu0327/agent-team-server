package com.guyu.agentteam.service;

import com.guyu.agentteam.dto.MessageDto;
import com.guyu.agentteam.entity.Agent;
import com.guyu.agentteam.entity.Conversation;
import com.guyu.agentteam.entity.ConversationMember;
import com.guyu.agentteam.entity.Message;
import com.guyu.agentteam.entity.ModelPreset;
import com.guyu.agentteam.repository.AgentRepository;
import com.guyu.agentteam.repository.ConversationMemberRepository;
import com.guyu.agentteam.service.orchestration.AgentModelFactory;
import com.guyu.agentteam.service.orchestration.OrchestrationService;
import com.guyu.agentteam.service.tool.WorkspaceFileTools;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.tool.Toolkit;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 回复规则：@了谁只有被@的人回复，没@则群里全员依次回复（后一个人能看到前一个人的发言）。
 * 编排者例外：被点名或群里存在编排者时，由编排者单独运行 AgentScope 协作循环，按需调用其他成员。
 * 所有回复都走 AgentScope ReAct 循环，智能体可调用工作区文件工具（write_file/read_file/list_dir）。
 */
@Service
public class ChatStreamService {

    private static final int MAX_ITERS = 10;
    private static final Duration REPLY_TIMEOUT = Duration.ofMinutes(4);

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final ConversationMemberRepository members;
    private final AgentRepository agents;
    private final AgentModelFactory modelFactory;
    private final WorkspaceFileTools fileTools;
    private final ConversationStreamSupport support;
    private final OrchestrationService orchestration;

    public ChatStreamService(ConversationMemberRepository members,
                             AgentRepository agents, AgentModelFactory modelFactory,
                             WorkspaceFileTools fileTools, ConversationStreamSupport support,
                             OrchestrationService orchestration) {
        this.members = members;
        this.agents = agents;
        this.modelFactory = modelFactory;
        this.fileTools = fileTools;
        this.support = support;
        this.orchestration = orchestration;
    }

    public void stream(SseEmitter emitter, Conversation conv, Message userMsg) {
        support.send(emitter, "user_message", MessageDto.from(userMsg));
        executor.submit(() -> {
            try {
                for (Agent agent : responders(conv, userMsg.getContent())) {
                    replyOne(emitter, conv, agent);
                }
                support.send(emitter, "done", Map.of());
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
    }

    private void replyOne(SseEmitter emitter, Conversation conv, Agent agent) {
        Optional<ModelPreset> presetOpt = support.presetOf(agent);
        if (presetOpt.isEmpty()) {
            support.persistError(conv, agent, "「" + agent.getName() + "」的模型预设缺失，请先在编辑页选择模型预设");
            support.send(emitter, "reply_error", Map.of("agentId", agent.getId(),
                    "error", "「" + agent.getName() + "」未关联模型预设，已跳过"));
            return;
        }
        ModelPreset preset = presetOpt.get();
        if (isBlank(preset.getApiKey()) || isBlank(preset.getBaseUrl())) {
            support.persistError(conv, agent, "「" + agent.getName() + "」的模型预设缺少 API 地址或 Key，请先补全");
            support.send(emitter, "reply_error", Map.of("agentId", agent.getId(),
                    "error", "「" + agent.getName() + "」的模型预设未配置完整，已跳过"));
            return;
        }

        if (isOrchestrator(agent)) {
            orchestration.run(emitter, conv, agent, preset);
            return;
        }

        ConversationStreamSupport.SegState seg = new ConversationStreamSupport.SegState();
        try (ReActAgent react = ReActAgent.builder()
                .name(agent.getName())
                .sysPrompt(sysPromptOf(agent))
                .model(modelFactory.create(agent, preset))
                .toolkit(toolkitOf())
                .maxIters(MAX_ITERS)
                .build()) {
            react.streamEvents(support.historyMsgs(conv.getId()))
                    .doOnNext(ev -> {
                        if (ev.getType() == AgentEventType.TEXT_BLOCK_DELTA) {
                            String delta = ((TextBlockDeltaEvent) ev).getDelta();
                            if (seg.msg.get() == null && delta.isBlank()) return;
                            if (seg.msg.get() == null) support.openSegment(emitter, conv, agent, seg);
                            seg.text.append(delta);
                            support.send(emitter, "delta", Map.of(
                                    "messageId", seg.msg.get().getId(),
                                    "delta", delta,
                                    "conversationId", conv.getId()));
                        } else if (ev.getType() == AgentEventType.TOOL_CALL_START) {
                            support.closeSegment(emitter, conv, agent, seg);
                        }
                    })
                    .blockLast(REPLY_TIMEOUT);
            support.closeSegment(emitter, conv, agent, seg);
            if (seg.opened == 0) {
                support.persistError(conv, agent, "「" + agent.getName() + "」（模型未返回内容）");
                support.send(emitter, "reply_error", Map.of("agentId", agent.getId(), "error", "（模型未返回内容）"));
            }
        } catch (UncheckedIOException e) {
            // 客户端断开，SSE 已不可用
            throw e;
        } catch (Exception e) {
            String err = ConversationStreamSupport.isBlockingTimeout(e) ? "回复超时，已中止"
                    : (e.getMessage() == null ? e.toString() : e.getMessage());
            support.closeSegment(emitter, conv, agent, seg);
            support.persistError(conv, agent, "「" + agent.getName() + "」回复失败：" + err);
            support.send(emitter, "reply_error", Map.of("agentId", agent.getId(), "error", err));
        }
    }

    private String sysPromptOf(Agent agent) {
        String base = support.isBlank(agent.getSystemPrompt()) ? "" : agent.getSystemPrompt().trim();
        return support.isBlank(base) ? fileTools.promptNote() : base + "\n\n" + fileTools.promptNote();
    }

    private Toolkit toolkitOf() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(fileTools);
        return toolkit;
    }

    private List<Agent> responders(Conversation conv, String content) {
        List<Agent> list = members.findByConversationIdOrderByCreatedAtAsc(conv.getId()).stream()
                .map(ConversationMember::getAgentId)
                .map(agents::findById)
                .flatMap(Optional::stream)
                .toList();
        if (list.isEmpty()) {
            return list;
        }
        if ("single".equals(conv.getType())) {
            return List.of(list.get(0));
        }
        List<Agent> mentioned = list.stream().filter(a -> content.contains("@" + a.getName())).toList();
        if (!mentioned.isEmpty()) {
            // 被点名的若是编排者，由他单独协调；否则被点名的依次回复
            return mentioned.stream().filter(this::isOrchestrator).findFirst()
                    .map(List::of)
                    .orElse(mentioned);
        }
        // 没人被点名：群里有编排者则由编排者协调，否则全员依次回复
        return list.stream().filter(this::isOrchestrator).findFirst()
                .map(List::of)
                .orElse(list);
    }

    private boolean isOrchestrator(Agent a) {
        return Boolean.TRUE.equals(a.getIsOrchestrator());
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
