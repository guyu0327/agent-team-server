package com.guyu.agentteam.service.orchestration;

import com.guyu.agentteam.dto.ConversationDto;
import com.guyu.agentteam.dto.GroupChatRequest;
import com.guyu.agentteam.entity.Agent;
import com.guyu.agentteam.entity.Conversation;
import com.guyu.agentteam.entity.ConversationMember;
import com.guyu.agentteam.entity.Message;
import com.guyu.agentteam.entity.ModelPreset;
import com.guyu.agentteam.repository.AgentRepository;
import com.guyu.agentteam.repository.ConversationMemberRepository;
import com.guyu.agentteam.service.ConversationService;
import com.guyu.agentteam.service.ConversationStreamSupport;
import com.guyu.agentteam.service.FileGrantService;
import com.guyu.agentteam.service.tool.WorkspaceFileTools;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 编排者回复：用 AgentScope ReAct 循环驱动，配合 list_team / create_team / delegate / finish 四个工具实现团队协作。
 * 协作透明：编排者的每段发言、成员的输出都作为真实消息流式写入目标会话；
 * create_team 建群后协作过程进群，最终总结仍发回用户发起请求的会话。
 */
@Service
public class OrchestrationService {

    private static final int MAX_ITERS = 10;
    private static final Duration OVERALL_TIMEOUT = Duration.ofMinutes(8);
    private static final Duration MEMBER_TIMEOUT = Duration.ofMinutes(4);

    private final AgentRepository agents;
    private final ConversationMemberRepository members;
    private final ConversationStreamSupport support;
    private final ConversationService conversationService;
    private final AgentModelFactory modelFactory;
    private final WorkspaceFileTools fileTools;
    private final FileGrantService fileGrants;
    /** 进行中的编排运行：会话ID（含协作中创建的项目群ID）→ 运行句柄，用于用户终止 */
    private final Map<String, RunHandle> runs = new ConcurrentHashMap<>();

    public OrchestrationService(AgentRepository agents, ConversationMemberRepository members,
                                ConversationStreamSupport support, ConversationService conversationService,
                                AgentModelFactory modelFactory, WorkspaceFileTools fileTools,
                                FileGrantService fileGrants) {
        this.agents = agents;
        this.members = members;
        this.support = support;
        this.conversationService = conversationService;
        this.modelFactory = modelFactory;
        this.fileTools = fileTools;
        this.fileGrants = fileGrants;
    }

    public void run(SseEmitter emitter, Conversation conv, Agent orchestrator, ModelPreset preset) {
        List<Agent> team = teamPool(conv, orchestrator);
        ConversationStreamSupport.SegState seg = new ConversationStreamSupport.SegState();
        AtomicBoolean finished = new AtomicBoolean(false);
        RunHandle handle = new RunHandle();
        handle.keys.add(conv.getId());
        runs.put(conv.getId(), handle);
        // 协作目标会话：默认是当前会话，create_team 后切到项目群
        AtomicReference<Conversation> target = new AtomicReference<>(conv);
        AtomicReference<String> createdGroupId = new AtomicReference<>();

        Toolkit toolkit = new Toolkit();
        // 文件工具作用域跟随当前协作目标会话：建群前用发起会话授权，建群后切到项目群，群里的撤销立即生效
        toolkit.registerTool(fileTools.scoped(() -> target.get().getId()));
        toolkit.registerTool(new TeamTools(emitter, conv, target, orchestrator, team, seg, finished, createdGroupId, handle));

        sendCoordination(emitter, "coordination_start", orchestrator, conv.getId());

        try (ReActAgent agent = ReActAgent.builder()
                .name(orchestrator.getName())
                .sysPrompt(buildSysPrompt(orchestrator, team, conv.getId()))
                .model(modelFactory.create(orchestrator, preset))
                .toolkit(toolkit)
                .maxIters(MAX_ITERS)
                .build()) {
            agent.streamEvents(support.historyMsgs(conv.getId()))
                    .doOnNext(e -> {
                        if (handle.cancelled.get()) throw new CancelledException();
                        if (e.getType() == AgentEventType.TEXT_BLOCK_DELTA) {
                            if (finished.get()) return;
                            String delta = ((TextBlockDeltaEvent) e).getDelta();
                            if (seg.msg.get() == null && delta.isBlank()) return;
                            if (seg.msg.get() == null) support.openSegment(emitter, target.get(), orchestrator, seg);
                            seg.text.append(delta);
                            support.send(emitter, "delta", Map.of(
                                    "messageId", seg.msg.get().getId(),
                                    "delta", delta,
                                    "conversationId", target.get().getId()));
                        } else if (e.getType() == AgentEventType.TOOL_CALL_START) {
                            support.closeSegment(emitter, target.get(), orchestrator, seg);
                        }
                    })
                    .blockLast(OVERALL_TIMEOUT);
            support.closeSegment(emitter, target.get(), orchestrator, seg);
        } catch (UncheckedIOException e) {
            // 客户端断开，SSE 已不可用
            handle.keys.forEach(runs::remove);
            throw e;
        } catch (CancelledException e) {
            support.closeSegment(emitter, target.get(), orchestrator, seg);
            // finish 已提交过总结就不补终止提示，避免画蛇添足
            if (!finished.get()) {
                support.persistError(conv, orchestrator, "协作已被用户手动终止");
                support.send(emitter, "reply_error", Map.of(
                        "agentId", orchestrator.getId(),
                        "error", "协作已被用户手动终止",
                        "conversationId", conv.getId()));
            }
        } catch (Exception e) {
            support.closeSegment(emitter, target.get(), orchestrator, seg);
            String err = ConversationStreamSupport.isBlockingTimeout(e) ? "编排超时，已中止"
                    : (e.getMessage() == null ? e.toString() : e.getMessage());
            support.persistError(conv, orchestrator, "「" + orchestrator.getName() + "」编排失败：" + err);
            support.send(emitter, "reply_error", Map.of(
                    "agentId", orchestrator.getId(),
                    "error", "编排失败：" + err,
                    "conversationId", conv.getId()));
        }
        sendCoordination(emitter, "coordination_end", orchestrator, conv.getId());
        String groupId = createdGroupId.get();
        if (groupId != null) {
            sendCoordination(emitter, "coordination_end", orchestrator, groupId);
        }
        handle.keys.forEach(runs::remove);
    }

    /** 终止指定会话进行中的编排协作，返回是否找到了运行中的协作 */
    public boolean stop(String conversationId) {
        RunHandle handle = runs.get(conversationId);
        if (handle == null) return false;
        handle.cancelled.set(true);
        return true;
    }

    /** 一次编排运行的取消句柄：cancelled 置位后，流式回调在下一个事件处中止 */
    private static final class RunHandle {
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        /** 该运行注册过的所有会话ID（发起请求的单聊 + 协作中创建的项目群） */
        final Set<String> keys = ConcurrentHashMap.newKeySet();
    }

    /** 用户终止协作时向流中抛出的控制流异常 */
    private static final class CancelledException extends RuntimeException {
    }

    /** 协调状态事件：客户端断开后静默忽略 */
    private void sendCoordination(SseEmitter emitter, String event, Agent agent, String conversationId) {
        try {
            support.send(emitter, event, Map.of("agentId", agent.getId(), "conversationId", conversationId));
        } catch (UncheckedIOException ignored) {
            // 客户端已断开
        }
    }

    /** 可委派的团队成员：群聊取群成员，单聊取同一用户的全部其他智能体 */
    private List<Agent> teamPool(Conversation conv, Agent orchestrator) {
        if ("group".equals(conv.getType())) {
            return members.findByConversationIdOrderByCreatedAtAsc(conv.getId()).stream()
                    .map(ConversationMember::getAgentId)
                    .map(agents::findById)
                    .flatMap(Optional::stream)
                    .filter(a -> !a.getId().equals(orchestrator.getId()))
                    .toList();
        }
        return agents.findByOrderByCreatedAtAsc().stream()
                .filter(a -> a.getUserId() != null && a.getUserId().equals(orchestrator.getUserId()))
                .filter(a -> !a.getId().equals(orchestrator.getId()))
                .toList();
    }

    private String buildSysPrompt(Agent orchestrator, List<Agent> team, String conversationId) {
        StringBuilder sb = new StringBuilder();
        String base = orchestrator.getSystemPrompt();
        if (!support.isBlank(base)) {
            sb.append(base.trim()).append("\n\n");
        }
        sb.append("你是智能体团队的编排者（").append(orchestrator.getName())
                .append("），职责是理解用户需求，协调团队成员分工完成，并给出最终总结。\n")
                .append("协作规范：\n")
                .append("1. 先调用 list_team 了解可委派的成员及其职责。\n")
                .append("2. 只要本次任务需要 2 个及以上成员配合（例如一人开发、另一人测试或评审），就必须在第一次 delegate 之前先调用 create_team 创建项目群，之后所有安排和委派都在群里进行。只有确定全程只需要 1 个成员独立完成时，才可以不建群。\n")
                .append("3. 把需求拆解为子任务，用 delegate 依次委派给合适的成员；任务描述要完整明确，包含必要的上下文、要求和期望产出。\n")
                .append("4. 成员的输出会直接展示给用户，不要复述成员的完整输出；你只需在每次委派前后简短说明你的安排和判断。\n")
                .append("5. 单次只委派一个成员，等他的结果返回后再决定下一步（例如先实现再验证）。\n");
        if (team.isEmpty()) {
            sb.append("6. 当前团队没有其他成员，你自己完成任务后调用 finish 总结。\n");
        } else {
            sb.append("6. 所有工作完成后必须调用 finish，summary 写给用户的最终总结答复，总结会发回用户发起请求的会话。\n");
        }
        sb.append("7. 如果任务要求把成果写到文件，委派时要把期望的输出路径（相对工作区根目录）写进任务描述，并提醒成员调用 write_file 完成写入。\n");
        sb.append(fileTools.promptNote(conversationId)).append("\n");
        return sb.toString();
    }

    /** 注册给编排者 ReAct 循环的协作工具 */
    public class TeamTools {

        private final SseEmitter emitter;
        /** 用户发起请求的会话：finish 总结固定发回这里 */
        private final Conversation originConv;
        /** 协作目标会话：create_team 后切到项目群 */
        private final AtomicReference<Conversation> target;
        private final Agent orchestrator;
        private final List<Agent> team;
        private final ConversationStreamSupport.SegState seg;
        private final AtomicBoolean finished;
        private final AtomicReference<String> createdGroupId;
        private final RunHandle handle;
        /** 本次协作已委派过的成员 ID：用于强制「单聊未建群时只能委派一个成员」 */
        private final Set<String> delegatedMembers = new LinkedHashSet<>();

        TeamTools(SseEmitter emitter, Conversation originConv, AtomicReference<Conversation> target,
                  Agent orchestrator, List<Agent> team, ConversationStreamSupport.SegState seg,
                  AtomicBoolean finished, AtomicReference<String> createdGroupId, RunHandle handle) {
            this.emitter = emitter;
            this.originConv = originConv;
            this.target = target;
            this.orchestrator = orchestrator;
            this.team = team;
            this.seg = seg;
            this.finished = finished;
            this.createdGroupId = createdGroupId;
            this.handle = handle;
        }

        @Tool(name = "list_team", description = "查看当前可委派的团队成员名单及其职责", readOnly = true)
        public String listTeam() {
            if (team.isEmpty()) {
                return "当前团队没有其他成员，你需要自己完成任务并调用 finish 总结。";
            }
            StringBuilder sb = new StringBuilder("团队成员：\n");
            for (Agent a : team) {
                sb.append("- ").append(a.getName());
                if (!support.isBlank(a.getDescription())) {
                    sb.append("：").append(a.getDescription().trim());
                }
                sb.append("\n");
            }
            return sb.toString();
        }

        @Tool(name = "create_team", description =
                "创建一个项目群，把完成该任务所需的成员拉进群里协作。需要 2 个及以上成员配合的任务必须先创建项目群再开始委派"
                        + "（例如开发完成后需要另一个人测试或评审）。创建后你的安排和委派的成员输出都会展示在群里，"
                        + "最终总结仍会发回用户发起请求的会话")
        public String createTeam(
                @ToolParam(name = "name", required = true, description = "群名称，简洁明了，如：排序功能开发群") String name,
                @ToolParam(name = "members", required = true, description = "要拉入群的成员名称，多个用逗号分隔，必须来自 list_team 名单") String members) {
            if (finished.get()) {
                return "任务已结束，无需建群";
            }
            if (!"single".equals(originConv.getType())) {
                return "当前已在群聊中协作，无需再建群";
            }
            if (createdGroupId.get() != null) {
                return "项目群已创建过，请继续在该群里协作";
            }
            List<Agent> picked = new ArrayList<>();
            List<String> invalid = new ArrayList<>();
            for (String raw : members.split("[,，、\\s]+")) {
                String n = raw.trim();
                if (n.isEmpty()) continue;
                team.stream()
                        .filter(a -> a.getName().equals(n))
                        .findFirst()
                        .ifPresentOrElse(picked::add, () -> invalid.add(n));
            }
            if (picked.isEmpty()) {
                String names = team.stream().map(Agent::getName).collect(Collectors.joining("、"));
                String miss = invalid.isEmpty() ? "" : "找不到成员：" + String.join("、", invalid) + "。";
                return miss + "没有有效的成员可选。可用成员：" + (names.isEmpty() ? "（无）" : names);
            }
            List<String> memberIds = new ArrayList<>(picked.stream().map(Agent::getId).toList());
            if (!memberIds.contains(orchestrator.getId())) {
                memberIds.add(orchestrator.getId());
            }
            ConversationDto dto = conversationService.createGroup(
                    originConv.getUserId(), new GroupChatRequest(name.trim(), memberIds, null));
            createdGroupId.set(dto.id());
            // 项目群沿用发起会话的文件授权：群里可见、直接 @ 成员时也能访问同一批目录
            fileGrants.copyGrants(originConv.getId(), dto.id());
            // 项目群也纳入终止注册表：在群里也能终止本次协作
            handle.keys.add(dto.id());
            runs.put(dto.id(), handle);
            target.set(conversationService.getEntity(dto.id()));
            support.send(emitter, "conversation_created", dto);
            sendCoordination(emitter, "coordination_start", orchestrator, dto.id());
            String joined = picked.stream().map(Agent::getName).collect(Collectors.joining("、"));
            return "项目群「" + dto.name() + "」已创建，成员：" + joined
                    + "。后续你的安排和委派成员的输出都会展示在该群里，请继续用 delegate 推进任务；"
                    + "完成后调用 finish，总结会自动发回与用户的单聊。";
        }

        @Tool(name = "delegate", description =
                "把一个子任务委派给团队成员。成员用它自己的人设和模型独立完成任务，"
                        + "他的输出会直接展示在当前协作会话中，完整结果返回给你")
        public String delegate(
                @ToolParam(name = "member", required = true, description = "成员名称，必须来自 list_team 返回的名单") String member,
                @ToolParam(name = "task", required = true, description = "委派给他的完整任务描述，包含必要的上下文和要求") String task) {
            if (finished.get()) {
                return "任务已结束，不再接受新的委派";
            }
            Agent targetAgent = team.stream()
                    .filter(a -> a.getName().equals(member))
                    .findFirst()
                    .orElse(null);
            if (targetAgent == null) {
                String names = team.stream().map(Agent::getName).collect(Collectors.joining("、"));
                return "找不到成员「" + member + "」。可用成员：" + (names.isEmpty() ? "（无）" : names);
            }
            // 硬性约束：单聊未建群时只允许委派同一个成员，引入第二个成员必须先 create_team
            if ("single".equals(originConv.getType()) && createdGroupId.get() == null
                    && team.size() > 1 && !delegatedMembers.isEmpty()
                    && !delegatedMembers.contains(targetAgent.getId())) {
                return "单聊中只能委派一个成员。要引入其他成员协作，必须先调用 create_team 创建项目群，"
                        + "把所需成员拉进群后再继续委派。";
            }
            delegatedMembers.add(targetAgent.getId());
            Conversation conv = target.get();
            Optional<ModelPreset> p = support.presetOf(targetAgent);
            if (p.isEmpty() || support.isBlank(p.get().getApiKey()) || support.isBlank(p.get().getBaseUrl())) {
                support.persistError(conv, targetAgent, "「" + targetAgent.getName() + "」的模型预设缺失或不完整，无法参与协作");
                support.send(emitter, "reply_error", Map.of(
                        "agentId", targetAgent.getId(),
                        "error", "「" + targetAgent.getName() + "」模型预设未配置完整，已跳过",
                        "conversationId", conv.getId()));
                return "成员「" + targetAgent.getName() + "」的模型预设未配置完整，无法委派给他，请选择其他成员或自己完成。";
            }

            closeSegment();
            Message placeholder = support.saveMessage(conv, targetAgent, "", "text");
            support.send(emitter, "reply_start", Map.of(
                    "messageId", placeholder.getId(),
                    "agentId", targetAgent.getId(),
                    "conversationId", conv.getId()));

            StringBuilder acc = new StringBuilder();
            AtomicReference<Msg> result = new AtomicReference<>();
            // 成员的文件工具跟随当前协作会话：建群后用群内授权副本，群里撤销对成员立即生效
            Toolkit memberToolkit = new Toolkit();
            memberToolkit.registerTool(fileTools.scoped(conv.getId()));
            String memberNote = fileTools.promptNote(conv.getId());
            String memberSysPrompt = support.isBlank(targetAgent.getSystemPrompt())
                    ? memberNote
                    : targetAgent.getSystemPrompt().trim() + "\n\n" + memberNote;
            try (ReActAgent memberAgent = ReActAgent.builder()
                    .name(targetAgent.getName())
                    .sysPrompt(memberSysPrompt)
                    .model(modelFactory.create(targetAgent, p.get()))
                    .toolkit(memberToolkit)
                    .maxIters(MAX_ITERS)
                    .build()) {
                memberAgent.streamEvents(new UserMessage(task))
                        .doOnNext(ev -> {
                            if (handle.cancelled.get()) throw new CancelledException();
                            if (ev.getType() == AgentEventType.TEXT_BLOCK_DELTA) {
                                String d = ((TextBlockDeltaEvent) ev).getDelta();
                                acc.append(d);
                                support.send(emitter, "delta", Map.of(
                                        "messageId", placeholder.getId(),
                                        "delta", d,
                                        "conversationId", conv.getId()));
                            } else if (ev.getType() == AgentEventType.AGENT_RESULT) {
                                result.set(((AgentResultEvent) ev).getResult());
                            }
                        })
                        .blockLast(MEMBER_TIMEOUT);
            } catch (UncheckedIOException e) {
                throw e;
            } catch (CancelledException e) {
                // 用户终止：保留成员已流出的部分内容，无内容则标记为错误
                if (acc.isEmpty()) {
                    support.markError(placeholder, "（协作已被用户终止）");
                    support.send(emitter, "reply_error", Map.of(
                            "messageId", placeholder.getId(),
                            "agentId", targetAgent.getId(),
                            "error", "协作已被用户终止",
                            "conversationId", conv.getId()));
                } else {
                    placeholder.setContent(acc.toString());
                    support.persist(placeholder);
                    support.touchConversation(conv, targetAgent, acc.toString());
                    support.send(emitter, "reply_end", Map.of(
                            "messageId", placeholder.getId(),
                            "agentId", targetAgent.getId(),
                            "content", acc.toString(),
                            "conversationId", conv.getId()));
                }
                return "协作已被用户终止。";
            } catch (Exception e) {
                String err = ConversationStreamSupport.isBlockingTimeout(e) ? "执行超时"
                        : (e.getMessage() == null ? e.toString() : e.getMessage());
                support.markError(placeholder, "执行失败：" + err);
                support.send(emitter, "reply_error", Map.of(
                        "messageId", placeholder.getId(),
                        "agentId", targetAgent.getId(),
                        "error", "执行失败：" + err,
                        "conversationId", conv.getId()));
                return "成员「" + targetAgent.getName() + "」执行失败（" + err + "），请决定下一步。";
            }

            String full = result.get() != null && !support.isBlank(result.get().getTextContent())
                    ? result.get().getTextContent()
                    : acc.toString();
            if (support.isBlank(full)) {
                support.markError(placeholder, "（模型未返回内容）");
                support.send(emitter, "reply_error", Map.of(
                        "messageId", placeholder.getId(),
                        "agentId", targetAgent.getId(),
                        "error", "（模型未返回内容）",
                        "conversationId", conv.getId()));
                return "成员「" + targetAgent.getName() + "」没有返回内容，请决定下一步。";
            }
            placeholder.setContent(full);
            support.persist(placeholder);
            support.touchConversation(conv, targetAgent, full);
            support.send(emitter, "reply_end", Map.of(
                    "messageId", placeholder.getId(),
                    "agentId", targetAgent.getId(),
                    "content", full,
                    "conversationId", conv.getId()));
            return "成员「" + targetAgent.getName() + "」已完成任务，输出如下：\n\n" + full
                    + "\n\n请继续协调其他成员，或调用 finish 给用户总结。";
        }

        @Tool(name = "finish", description = "任务完成后调用，把给用户的最终总结答复提交出来，结束本次协作。总结会发回用户发起请求的会话")
        public String finish(
                @ToolParam(name = "summary", required = true, description = "给用户的最终总结答复") String summary) {
            if (finished.getAndSet(true)) {
                return "任务已结束";
            }
            closeSegment();
            Message m = support.saveMessage(originConv, orchestrator, "", "text");
            support.send(emitter, "reply_start", Map.of(
                    "messageId", m.getId(),
                    "agentId", orchestrator.getId(),
                    "conversationId", originConv.getId()));
            m.setContent(summary);
            support.persist(m);
            support.touchConversation(originConv, orchestrator, summary);
            support.send(emitter, "reply_end", Map.of(
                    "messageId", m.getId(),
                    "agentId", orchestrator.getId(),
                    "content", summary,
                    "conversationId", originConv.getId()));
            return "总结已提交给用户，本次任务结束。";
        }

        /** 结束编排者当前发言段（委派的成员气泡要接在它后面） */
        private void closeSegment() {
            support.closeSegment(emitter, target.get(), orchestrator, seg);
        }
    }
}
