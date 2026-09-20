package com.guyu.agentteam.service.orchestration;

import com.guyu.agentteam.dto.ConversationDto;
import com.guyu.agentteam.dto.GroupChatRequest;
import com.guyu.agentteam.dto.MessageDto;
import com.guyu.agentteam.entity.Agent;
import com.guyu.agentteam.entity.AppLog;
import com.guyu.agentteam.entity.Conversation;
import com.guyu.agentteam.entity.ConversationMember;
import com.guyu.agentteam.entity.Message;
import com.guyu.agentteam.entity.ModelPreset;
import com.guyu.agentteam.repository.AgentRepository;
import com.guyu.agentteam.repository.ConversationMemberRepository;
import com.guyu.agentteam.repository.MessageRepository;
import com.guyu.agentteam.service.AgentMemoryService;
import com.guyu.agentteam.service.AppLogService;
import com.guyu.agentteam.service.CoordinationLimitsService;
import com.guyu.agentteam.service.ContextCompressionService;
import com.guyu.agentteam.service.ConversationService;
import com.guyu.agentteam.service.ConversationStreamSupport;
import com.guyu.agentteam.service.FileGrantService;
import com.guyu.agentteam.service.MessageService;
import com.guyu.agentteam.service.OpApprovalService;
import com.guyu.agentteam.service.tool.ImageGenerationTools;
import com.guyu.agentteam.service.tool.OpRequestSink;
import com.guyu.agentteam.service.tool.ViewImageTools;
import com.guyu.agentteam.service.tool.WorkspaceFileTools;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.accumulator.TextAccumulator;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Mono;

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

    /** ReAct 迭代不设上限（int 最大值等效于关闭），总时长由「协作限制」设置兜底 */
    private static final int MAX_ITERS = Integer.MAX_VALUE;

    private final AgentRepository agents;
    private final ConversationMemberRepository members;
    private final ConversationStreamSupport support;
    private final ConversationService conversationService;
    private final AgentModelFactory modelFactory;
    private final WorkspaceFileTools fileTools;
    private final ImageGenerationTools imageTools;
    private final ViewImageTools viewTools;
    private final FileGrantService fileGrants;
    private final OpApprovalService approval;
    private final AppLogService appLogs;
    private final CoordinationLimitsService limitsService;
    private final ContextCompressionService compression;
    private final AgentMemoryService memoryService;
    private final com.guyu.agentteam.service.tool.ScheduledTaskTools taskTools;
    private final MessageService messageService;
    private final MessageRepository messages;
    /** 进行中的编排运行：会话ID（含协作中创建的项目群ID）→ 运行句柄，用于用户终止 */
    private final Map<String, RunHandle> runs = new ConcurrentHashMap<>();

    public OrchestrationService(AgentRepository agents, ConversationMemberRepository members,
                                ConversationStreamSupport support, ConversationService conversationService,
                                AgentModelFactory modelFactory, WorkspaceFileTools fileTools,
                                ImageGenerationTools imageTools, ViewImageTools viewTools,
                                FileGrantService fileGrants, OpApprovalService approval,
                                AppLogService appLogs, CoordinationLimitsService limitsService,
                                ContextCompressionService compression,
                                AgentMemoryService memoryService,
                                com.guyu.agentteam.service.tool.ScheduledTaskTools taskTools,
                                MessageService messageService, MessageRepository messages) {
        this.agents = agents;
        this.members = members;
        this.support = support;
        this.conversationService = conversationService;
        this.modelFactory = modelFactory;
        this.fileTools = fileTools;
        this.imageTools = imageTools;
        this.viewTools = viewTools;
        this.fileGrants = fileGrants;
        this.approval = approval;
        this.appLogs = appLogs;
        this.limitsService = limitsService;
        this.compression = compression;
        this.memoryService = memoryService;
        this.taskTools = taskTools;
        this.messageService = messageService;
        this.messages = messages;
    }

    public void run(SseEmitter emitter, Conversation conv, Agent orchestrator, ModelPreset preset,
                    ConversationStreamSupport.TaskTag taskTag) {
        List<Agent> team = teamPool(conv, orchestrator);
        RunHandle handle = new RunHandle();
        handle.taskTag = taskTag;
        handle.keys.add(conv.getId());
        runs.put(conv.getId(), handle);
        // 协作目标会话：默认是当前会话，create_team 后切到项目群
        AtomicReference<Conversation> target = new AtomicReference<>(conv);
        AtomicReference<String> createdGroupId = new AtomicReference<>();

        sendCoordination(emitter, "coordination_start", orchestrator, conv.getId());
        // 最多两轮：任务触发轮若整轮零工具调用（只输出任务卡/计划文本，未实际执行），追加纠正指令自动重试一次
        for (int attempt = 0; attempt < 2; attempt++) {
            boolean settled = runAgentRound(emitter, conv, target, orchestrator, preset, team, handle, createdGroupId);
            if (settled || taskTag == null || attempt > 0 || handle.stopRequested.get()) break;
            String correction = "刚才一轮你只输出了纯文本（任务卡 / 计划 / 总结），没有调用任何工具，任务并没有实际开始执行。\n"
                    + "请立即实际执行任务内容：需要成员参与就实际调用 delegate 工具把任务派给成员（当前会话通常已是任务项目群，无需再建群），"
                    + "需要写改文件就实际调用文件工具；不要再用文字总结代替真实执行。";
            Message m = messageService.createUserMessage(conv, correction, List.of());
            m.setTaskId(taskTag.taskId());
            m.setTaskName(taskTag.taskName());
            messages.save(m);
            support.send(emitter, "user_message", MessageDto.from(m));
            appLogs.record(AppLog.TYPE_TASK, conv.getId(), orchestrator.getId(),
                    "定时任务「" + taskTag.taskName() + "」触发轮只输出文本未实际执行，已追加纠正指令自动重试一次");
            compression.compactIfNeeded(conv, orchestrator);
        }
        // 协作完整走完才把发起会话的图片标记为已阅（被终止过的不标）
        if (!handle.stopRequested.get()) {
            compression.markImagesConsumed(conv.getId());
        }
        sendCoordination(emitter, "coordination_end", orchestrator, conv.getId());
        String groupId = createdGroupId.get();
        if (groupId != null) {
            sendCoordination(emitter, "coordination_end", orchestrator, groupId);
        }
        handle.keys.forEach(runs::remove);
    }

    /**
     * 跑一轮编排者的 ReAct 循环。
     * 返回 true 表示本轮已「尘埃落定」无需重试：实际调用了工具、或被用户终止、或中途出错；
     * 返回 false 表示完整走完但零工具调用（纯文本回复）。
     */
    private boolean runAgentRound(SseEmitter emitter, Conversation conv, AtomicReference<Conversation> target,
                                  Agent orchestrator, ModelPreset preset, List<Agent> team, RunHandle handle,
                                  AtomicReference<String> createdGroupId) {
        ConversationStreamSupport.TaskTag taskTag = handle.taskTag;
        ConversationStreamSupport.SegState seg = new ConversationStreamSupport.SegState();
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicBoolean toolCalled = new AtomicBoolean(false);

        Toolkit toolkit = new Toolkit();
        // 文件工具作用域跟随当前协作目标会话：建群前用发起会话授权，建群后切到项目群，群里的撤销立即生效。
        // 任务触发回合（taskTag 非空）是后台自动执行：按任务配置自动放行写改/命令，而非等待无人响应的审批卡片
        boolean background = taskTag != null;
        OpRequestSink sink = (opType, opTarget, detail) ->
                approval.approve(emitter, orchestrator, target.get().getId(), opType, opTarget, detail,
                        background, taskTag != null && taskTag.autoWrite(), taskTag != null && taskTag.autoShell());
        toolkit.registerTool(fileTools.toolsFor(() -> target.get().getId(), sink));
        fileTools.registerShellTool(toolkit, () -> target.get().getId(), sink);
        imageTools.register(toolkit, orchestrator, support.imageListener(emitter, () -> target.get().getId(), orchestrator));
        viewTools.register(toolkit, () -> target.get().getId());
        toolkit.registerTool(new TeamTools(emitter, conv, target, orchestrator, team, seg, finished, createdGroupId, handle));
        // 定时任务工具：会话跟随协作目标（建群后任务绑定项目群），创建的任务归编排者名下
        taskTools.register(toolkit, () -> target.get().getId(), orchestrator::getId,
                () -> taskTag != null ? taskTag.taskName() : null);

        compression.compactIfNeeded(conv, orchestrator);

        ReActAgent agent = ReActAgent.builder()
                .name(orchestrator.getName())
                .sysPrompt(buildSysPrompt(orchestrator, team, conv.getId()))
                .model(modelFactory.create(orchestrator, preset))
                .toolkit(toolkit)
                .maxIters(MAX_ITERS)
                .modelExecutionConfig(ConversationStreamSupport.modelCallExecutionConfig())
                .longTermMemory(memoryService.store(orchestrator.getId()))
                .longTermMemoryMode(LongTermMemoryMode.AGENT_CONTROL)
                .build();
        handle.running.add(agent);
        boolean ok = true;
        try {
            agent.streamEvents(support.historyMsgs(conv.getId()))
                    .takeUntilOther(handle.cancelSignal())
                    .doOnNext(e -> {
                        if (e.getType() == AgentEventType.TEXT_BLOCK_DELTA) {
                            if (finished.get()) return;
                            String delta = ((TextBlockDeltaEvent) e).getDelta();
                            if (seg.msg.get() == null && delta.isBlank()) return;
                            if (seg.msg.get() == null) support.openSegment(emitter, target.get(), orchestrator, seg, handle.taskTag);
                            seg.text.add(TextBlock.builder().text(delta).build());
                            support.send(emitter, "delta", Map.of(
                                    "messageId", seg.msg.get().getId(),
                                    "delta", delta,
                                    "conversationId", target.get().getId()));
                        } else if (e.getType() == AgentEventType.TOOL_CALL_START) {
                            toolCalled.set(true);
                            support.closeSegment(emitter, target.get(), orchestrator, seg);
                        }
                    })
                    .blockLast(Duration.ofMinutes(limitsService.load().overallMinutes()));
            support.closeSegment(emitter, target.get(), orchestrator, seg);
            if (handle.stopRequested.get()) notifyCancelled(emitter, conv, orchestrator, finished);
        } catch (UncheckedIOException e) {
            // 客户端断开，SSE 已不可用
            handle.keys.forEach(runs::remove);
            throw e;
        } catch (Exception e) {
            ok = false;
            support.closeSegment(emitter, target.get(), orchestrator, seg);
            if (ConversationStreamSupport.isCancelSignal(e, handle.stopRequested)) {
                notifyCancelled(emitter, conv, orchestrator, finished);
            } else {
                String err = ConversationStreamSupport.isBlockingTimeout(e) ? "编排超时，已中止"
                        : (e.getMessage() == null ? e.toString() : e.getMessage());
                support.persistError(conv, orchestrator, "「" + orchestrator.getName() + "」编排失败：" + err);
                support.send(emitter, "reply_error", Map.of(
                        "agentId", orchestrator.getId(),
                        "error", "编排失败：" + err,
                        "conversationId", conv.getId()));
            }
        } finally {
            handle.running.remove(agent);
            agent.close();
        }
        return !ok || handle.stopRequested.get() || toolCalled.get();
    }

    /** 终止指定会话进行中的编排协作，返回是否找到了运行中的协作 */
    public boolean stop(String conversationId) {
        RunHandle handle = runs.get(conversationId);
        if (handle == null) return false;
        // 直接掐断在途的模型请求/审批等待：框架 interrupt 只在迭代间生效，长调用会拖住终止
        handle.requestCancel();
        handle.running.forEach(ReActAgent::interrupt);
        // 卡在审批等待的请求按拒绝唤醒，前端同步关闭卡片
        handle.keys.forEach(key -> approval.cancelAllForConversation(key));
        appLogs.record(AppLog.TYPE_COORDINATION, conversationId, null, "用户手动终止协作");
        return true;
    }

    /** 一次编排运行的取消句柄：登记运行中的 ReActAgent，终止时用框架 interrupt 中断其流式循环 */
    private static final class RunHandle {
        final AtomicBoolean stopRequested = new AtomicBoolean(false);
        /** 定时任务回合标注：任务触发的协作把本轮消息全部打上来源任务 */
        ConversationStreamSupport.TaskTag taskTag;
        /** 运行中的 ReActAgent（编排者本人 + 正在执行 delegate 的成员） */
        final Set<ReActAgent> running = ConcurrentHashMap.newKeySet();
        /** 该运行注册过的所有会话ID（发起请求的单聊 + 协作中创建的项目群） */
        final Set<String> keys = ConcurrentHashMap.newKeySet();
        /** 终止通知回调：每个流式调用独立注册；不能用共享 Future——订阅被取消会连带 cancel 连坐他人 */
        private final Set<Runnable> cancelListeners = ConcurrentHashMap.newKeySet();

        void requestCancel() {
            stopRequested.set(true);
            cancelListeners.forEach(Runnable::run);
        }

        /** 取消信号：终止时立即完成；每个订阅独立注册，流的正常结束只注销自己 */
        Mono<Void> cancelSignal() {
            return Mono.create(sink -> {
                Runnable notify = () -> sink.success(null);
                sink.onDispose(() -> cancelListeners.remove(notify));
                // 先注册后检查，堵住「注册前已终止」的竞态窗口；重复 success 幂等
                cancelListeners.add(notify);
                if (stopRequested.get()) {
                    notify.run();
                }
            });
        }
    }

    /** 终止提示：finish 已提交过总结就不补，避免画蛇添足 */
    private void notifyCancelled(SseEmitter emitter, Conversation conv, Agent orchestrator, AtomicBoolean finished) {
        if (finished.get()) return;
        support.persistError(conv, orchestrator, "协作已被用户手动终止");
        support.send(emitter, "reply_error", Map.of(
                "agentId", orchestrator.getId(),
                "error", "协作已被用户手动终止",
                "conversationId", conv.getId()));
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

    /** 日志用的任务描述摘要 */
    private static String excerpt(String text) {
        if (text == null) {
            return "";
        }
        String t = text.replaceAll("\\s+", " ").trim();
        return t.length() <= 200 ? t : t.substring(0, 200) + "…";
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
                .append("1. 先调用 list_team 了解可委派的成员及其职责和能力。\n")
                .append("2. 只要本次任务需要委派任何成员参与（哪怕只有 1 个），就必须在第一次 delegate 之前先调用 create_team 创建项目群，之后所有安排和委派都在群里进行——你、参与的成员和用户共同构成协作多方。只有完全不需要任何成员、你独立完成时，才可以不建群。若当前会话已经是群聊（如任务项目群），直接 delegate 即可，无需 create_team。\n")
                .append("3. 把需求拆解为子任务，用 delegate 依次委派给最合适的成员（按成员能力匹配，涉及生成/绘制图片的任务只能委派给具备图像生成能力的成员）；任务描述要完整明确，包含必要的上下文、要求和期望产出。\n")
                .append("4. 成员的输出会直接展示给用户，不要复述成员的完整输出；你只需在每次委派前后简短说明你的安排和判断。\n")
                .append("5. 单次只委派一个成员，等他的结果返回后再决定下一步（例如先实现再验证）。\n");
        if (team.isEmpty()) {
            sb.append("6. 当前团队没有其他成员，你自己完成任务后调用 finish 总结。\n");
        } else {
            sb.append("6. 所有工作完成后必须调用 finish，summary 写给用户的最终总结答复，总结会发回用户发起请求的会话。\n");
        }
        sb.append("7. 如果任务要求把成果写到文件，委派时要把期望的输出路径（相对工作区根目录）写进任务描述，并提醒成员调用 write_file 完成写入。\n");
        sb.append("8. 当用户要求定时、定期、每天/每周固定时间、每隔一段时间或到点执行/提醒某事时，这是要创建定时任务：必须调用 schedule_task 创建并向用户确认时间；只要该工作需要成员参与（由谁做、让谁做、谁来完成等），就必须把相关成员传给 member_ids（支持成员 id 或成员名字，可先 list_team 查成员）——到点触发时会自动创建任务项目群并组织协作。漏传 member_ids 会导致只有你一人执行，成员不会参与。此场景下禁止调用 create_team 建普通项目群、也不要立即 delegate 委派成员：普通群聊本身不会让工作定时发生。create_team 仅用于「现在就执行」的即时协作请求。已创建的任务可用 list_scheduled_tasks 查询、update_scheduled_task 修改、cancel_scheduled_task 取消，任务的执行结果会出现在「定时任务」页对应的任务会话中。\n");
        sb.append("9. 收到以「【定时任务触发·」开头的消息时，说明是某个已存在任务到点的自动触发：按规则 1-5 真实执行其中的任务内容——需要成员参与就实际调用 delegate（触发消息所在的会话通常已是任务项目群，此时直接 delegate，不要再 create_team），需要写改文件就实际调用文件工具；此场景禁止的只有调用 schedule_task 再创建新任务，其余协作工具照常使用。任务内容若说「以任务卡形式委派」，指把任务卡内容作为 delegate 的参数传给成员，绝不是把任务卡作为聊天文本输出。不要只在回复里输出任务卡、计划或总结来代替真实执行，也不要因为历史记录里出现过类似执行结果就跳过本轮。\n");
        // 成员能力清单：让编排者无需逐个试探就知道谁能绘图，委派才能精准
        List<Agent> drawers = team.stream().filter(imageTools::hasCapability).toList();
        if (imageTools.hasCapability(orchestrator)) {
            sb.append("你本人具备图像生成能力（generate_image 工具），可直接完成简单的生图请求。\n");
        }
        if (!drawers.isEmpty()) {
            sb.append("具备图像生成（绘图）能力的成员：")
                    .append(drawers.stream().map(Agent::getName).collect(Collectors.joining("、")))
                    .append("。涉及绘图/生成图片的子任务必须委派给这些成员，其余成员无法生成图片。\n");
        } else if (!team.isEmpty()) {
            sb.append("当前所有成员均不具备图像生成能力，涉及绘图的任务请自行完成或向用户说明。\n");
        }
        sb.append(fileTools.promptNote(conversationId)).append("\n");
        String digest = compression.digestOf(conversationId);
        if (!support.isBlank(digest)) {
            sb.append("\n【会话早期历史摘要】\n更早的完整对话已压缩为以下要点，请以此作为早期上下文：\n")
                    .append(digest.trim()).append("\n");
        }
        String memory = memoryService.promptBlock(orchestrator.getId());
        if (!support.isBlank(memory)) {
            sb.append('\n').append(memory).append('\n');
        }
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

        @Tool(name = "list_team", description = "查看当前可委派的团队成员名单及其职责与能力", readOnly = true)
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
                if (imageTools.hasCapability(a)) {
                    sb.append("［具备图像生成/绘图能力，可委派绘图任务］");
                }
                sb.append("\n");
            }
            return sb.toString();
        }

        @Tool(name = "create_team", description =
                "创建一个项目群，把完成该任务所需的成员拉进群里协作。只要需要委派成员参与（哪怕只有 1 个），"
                        + "必须先创建项目群再开始委派。创建后你的安排和委派的成员输出都会展示在群里，"
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
            appLogs.record(AppLog.TYPE_COORDINATION, conv.getId(), targetAgent.getId(),
                    "编排者委派任务给「" + targetAgent.getName() + "」：" + excerpt(task));
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
            Message placeholder = support.saveMessage(conv, targetAgent, "", "text", handle.taskTag);
            support.send(emitter, "reply_start", Map.of(
                    "messageId", placeholder.getId(),
                    "agentId", targetAgent.getId(),
                    "conversationId", conv.getId()));

            TextAccumulator acc = new TextAccumulator();
            AtomicReference<Msg> result = new AtomicReference<>();
            // 成员的文件工具跟随当前协作会话：建群后用群内授权副本，群里撤销对成员立即生效。
            // 任务触发回合里成员与编排者同策略：按任务配置自动放行/拒绝，不走无人响应的人工审批
            ConversationStreamSupport.TaskTag tag = handle.taskTag;
            boolean memberBackground = tag != null;
            OpRequestSink memberSink = (opType, opTarget, detail) ->
                    approval.approve(emitter, targetAgent, conv.getId(), opType, opTarget, detail,
                            memberBackground, tag != null && tag.autoWrite(), tag != null && tag.autoShell());
            Toolkit memberToolkit = new Toolkit();
            memberToolkit.registerTool(fileTools.toolsFor(conv.getId(), memberSink));
            fileTools.registerShellTool(memberToolkit, conv::getId, memberSink);
            imageTools.register(memberToolkit, targetAgent, support.imageListener(emitter, conv::getId, targetAgent));
            String memberNote = fileTools.promptNote(conv.getId());
            String memberSysPrompt = support.isBlank(targetAgent.getSystemPrompt())
                    ? memberNote
                    : targetAgent.getSystemPrompt().trim() + "\n\n" + memberNote;
            String memberMemory = memoryService.promptBlock(targetAgent.getId());
            if (!support.isBlank(memberMemory)) {
                memberSysPrompt += "\n\n" + memberMemory;
            }
            boolean cancelled = false;
            ReActAgent memberAgent = ReActAgent.builder()
                    .name(targetAgent.getName())
                    .sysPrompt(memberSysPrompt)
                    .model(modelFactory.create(targetAgent, p.get()))
                    .toolkit(memberToolkit)
                    .maxIters(MAX_ITERS)
                    .modelExecutionConfig(ConversationStreamSupport.modelCallExecutionConfig())
                    .longTermMemory(memoryService.store(targetAgent.getId()))
                    .longTermMemoryMode(LongTermMemoryMode.AGENT_CONTROL)
                    .build();
            handle.running.add(memberAgent);
            try {
                memberAgent.streamEvents(new UserMessage(task))
                        .takeUntilOther(handle.cancelSignal())
                        .doOnNext(ev -> {
                            if (ev.getType() == AgentEventType.TEXT_BLOCK_DELTA) {
                                String d = ((TextBlockDeltaEvent) ev).getDelta();
                                acc.add(TextBlock.builder().text(d).build());
                                support.send(emitter, "delta", Map.of(
                                        "messageId", placeholder.getId(),
                                        "delta", d,
                                        "conversationId", conv.getId()));
                            } else if (ev.getType() == AgentEventType.AGENT_RESULT) {
                                result.set(((AgentResultEvent) ev).getResult());
                            }
                        })
                        .blockLast(Duration.ofMinutes(limitsService.load().memberMinutes()));
            } catch (UncheckedIOException e) {
                throw e;
            } catch (Exception e) {
                if (!ConversationStreamSupport.isCancelSignal(e, handle.stopRequested)) {
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
                cancelled = true;
            } finally {
                handle.running.remove(memberAgent);
                memberAgent.close();
            }
            // 用户终止（ interrupt 报错或流被框架正常收尾两种形态都归到这里）：
            // 保留成员已流出的部分内容，无内容则标记为错误
            if (cancelled || handle.stopRequested.get()) {
                if (!acc.hasContent()) {
                    support.markError(placeholder, "（协作已被用户终止）");
                    support.send(emitter, "reply_error", Map.of(
                            "messageId", placeholder.getId(),
                            "agentId", targetAgent.getId(),
                            "error", "协作已被用户终止",
                            "conversationId", conv.getId()));
                } else {
                    placeholder.setContent(acc.getAccumulated());
                    support.persist(placeholder);
                    support.touchConversation(conv, targetAgent, acc.getAccumulated());
                    support.send(emitter, "reply_end", Map.of(
                            "messageId", placeholder.getId(),
                            "agentId", targetAgent.getId(),
                            "content", acc.getAccumulated(),
                            "conversationId", conv.getId()));
                }
                return "协作已被用户终止。";
            }

            String full = result.get() != null && !support.isBlank(result.get().getTextContent())
                    ? result.get().getTextContent()
                    : acc.getAccumulated();
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
            Message m = support.saveMessage(originConv, orchestrator, "", "text", handle.taskTag);
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
