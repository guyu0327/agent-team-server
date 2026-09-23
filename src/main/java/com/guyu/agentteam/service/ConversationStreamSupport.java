package com.guyu.agentteam.service;

import com.guyu.agentteam.common.Str;
import com.guyu.agentteam.common.Ids;
import com.guyu.agentteam.common.Images;
import com.guyu.agentteam.common.Json;
import com.guyu.agentteam.entity.Agent;
import com.guyu.agentteam.entity.AppLog;
import com.guyu.agentteam.entity.Conversation;
import com.guyu.agentteam.entity.ConversationFileGrant;
import com.guyu.agentteam.entity.Message;
import com.guyu.agentteam.entity.ModelPreset;
import com.guyu.agentteam.repository.ConversationRepository;
import com.guyu.agentteam.repository.MessageRepository;
import com.guyu.agentteam.repository.ModelPresetRepository;
import com.guyu.agentteam.service.tool.ImageGenerationTools;
import io.agentscope.core.agent.accumulator.TextAccumulator;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 普通回复与编排回复共用的 SSE 发送和消息持久化逻辑 */
@Service
public class ConversationStreamSupport {

    private final ObjectMapper mapper = Json.mapper();

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final ModelPresetRepository presets;
    private final AppLogService appLogs;
    private final ContextCompressionService compression;

    /** 漏斗里需要落库的运行事件 → 日志类型（op_request 由 OpApprovalService 单独记录，content 类事件不记） */
    private static final Map<String, String> LOGGED_EVENTS = Map.of(
            "coordination_start", AppLog.TYPE_COORDINATION,
            "coordination_end", AppLog.TYPE_COORDINATION,
            "discussion_start", AppLog.TYPE_DISCUSSION,
            "discussion_end", AppLog.TYPE_DISCUSSION,
            "image_start", AppLog.TYPE_IMAGE,
            "image_end", AppLog.TYPE_IMAGE);

    public ConversationStreamSupport(ConversationRepository conversations, MessageRepository messages,
                                     ModelPresetRepository presets, AppLogService appLogs,
                                     ContextCompressionService compression) {
        this.conversations = conversations;
        this.messages = messages;
        this.presets = presets;
        this.appLogs = appLogs;
        this.compression = compression;
    }

    public void send(SseEmitter emitter, String event, Object data) {
        String json = mapper.writeValueAsString(data);
        if (emitter instanceof Broadcast broadcast) {
            trackRound(broadcast.conversationId, event, data);
        }
        dispatch(emitter, event, json);
        logEvent(event, data);
    }

    /** 无响应流的后台场景（如微信通道切换标注落库）把事件直接扇出给会话观察者，前端消息流实时可见 */
    public void broadcastToWatchers(String conversationId, String event, Object data) {
        fanOut(conversationId, event, mapper.writeValueAsString(data));
        logEvent(event, data);
    }

    /**
     * 协作状态事件发往「非发起会话」（编排者单聊里新建的项目群）：响应流照发之外，
     * 再按目标会话实时扇出给观察者并登记回合快照供后续观察者重放。
     * 普通响应流回合默认不留快照，群里的「正在协调团队」条切换/重进后会凭空消失。
     */
    public void sendCrossConversation(SseEmitter emitter, String event, String conversationId, Object data) {
        String json = mapper.writeValueAsString(data);
        trackRound(conversationId, event, data);
        fanOut(conversationId, event, json);
        dispatch(emitter, event, json);
        logEvent(event, data);
    }

    /** 定时任务触发的回合用 Broadcast：事件扇出给该会话的观察者（events 常驻 SSE），普通发送仍走各自请求的响应流 */
    private void dispatch(SseEmitter emitter, String event, String json) {
        try {
            if (emitter instanceof Broadcast broadcast) {
                fanOut(broadcast.conversationId, event, json);
                return;
            }
            emitter.send(SseEmitter.event().name(event)
                    .data(json, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void fanOut(String conversationId, String event, String json) {
        Set<SseEmitter> set = watchers.get(conversationId);
        if (set == null || set.isEmpty()) {
            return;
        }
        for (SseEmitter watcher : set) {
            try {
                watcher.send(SseEmitter.event().name(event)
                        .data(json, MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                removeWatcher(conversationId, watcher);
            }
        }
    }

    private final Map<String, Set<SseEmitter>> watchers = new ConcurrentHashMap<>();

    /**
     * 任务触发回合的实时状态（仅 Broadcast 回合跟踪）：前端切页会断开/重连观察者，
     * 重连时重放这里的快照，协作状态与流式到一半的回复才不会在界面上凭空消失。
     */
    private final Map<String, ConvRoundState> roundStates = new ConcurrentHashMap<>();

    private static final class ConvRoundState {
        volatile String coordinator;
        volatile boolean discussing;
        final Map<String, InFlightSegment> segments = new ConcurrentHashMap<>();
    }

    private static final class InFlightSegment {
        final String agentId;
        final StringBuilder text = new StringBuilder();

        InFlightSegment(String agentId) {
            this.agentId = agentId;
        }
    }

    private void trackRound(String conversationId, String event, Object data) {
        ConvRoundState st;
        switch (event) {
            case "reply_start", "coordination_start" ->
                    st = roundStates.computeIfAbsent(conversationId, k -> new ConvRoundState());
            case "done" -> {
                roundStates.remove(conversationId);
                return;
            }
            default -> {
                st = roundStates.get(conversationId);
                if (st == null) return;
            }
        }
        Map<?, ?> p = data instanceof Map<?, ?> m ? m : Map.of();
        switch (event) {
            case "reply_start" -> st.segments.put(String.valueOf(p.get("messageId")),
                    new InFlightSegment(String.valueOf(p.get("agentId"))));
            case "delta" -> {
                InFlightSegment seg = st.segments.get(String.valueOf(p.get("messageId")));
                if (seg != null && p.get("delta") != null) {
                    seg.text.append(String.valueOf(p.get("delta")));
                }
            }
            case "reply_end" -> st.segments.remove(String.valueOf(p.get("messageId")));
            case "reply_error" -> {
                // 带 messageId 的是单段失败已落库；不带的是整轮错误，进行中的段一并丢弃
                if (p.get("messageId") != null) st.segments.remove(String.valueOf(p.get("messageId")));
                else st.segments.clear();
            }
            case "coordination_start" -> st.coordinator = String.valueOf(p.get("agentId"));
            case "coordination_end" -> st.coordinator = null;
            case "discussion_start" -> st.discussing = true;
            case "discussion_end" -> st.discussing = false;
            default -> { /* user_message / conversation_created / image_* 无需跟踪 */ }
        }
        if (st.coordinator == null && !st.discussing && st.segments.isEmpty()) {
            roundStates.remove(conversationId);
        }
    }

    /**
     * 回合收尾（幂等）：正常回合的 done 已清快照，这里不再重复发；
     * 异常路径（completeWithError）没发过 done，快照还在——补发一个让前端收尾，
     * 观察者的「协作中」横幅和半截流式输出才不会永远挂着。
     */
    public void endRound(String conversationId, SseEmitter emitter) {
        if (roundStates.remove(conversationId) != null) {
            try {
                send(emitter, "done", Map.of());
            } catch (Exception ignored) {
                // 连接已断/已 complete：快照已清，收尾目的已达成
            }
        }
    }

    /** 观察者：前端通过 /events 常驻 SSE 订阅某会话，接收该会话定时任务触发回合的全部事件 */
    public void registerWatcher(String conversationId, SseEmitter emitter) {
        watchers.computeIfAbsent(conversationId, k -> ConcurrentHashMap.newKeySet()).add(emitter);
        replayRound(conversationId, emitter);
        emitter.onCompletion(() -> removeWatcher(conversationId, emitter));
        emitter.onTimeout(() -> removeWatcher(conversationId, emitter));
    }

    /** 新观察者补发仍在进行的回合状态（重放的 delta 为全量快照，前端替换而非追加） */
    private void replayRound(String conversationId, SseEmitter emitter) {
        ConvRoundState st = roundStates.get(conversationId);
        if (st == null) return;
        try {
            if (st.coordinator != null) {
                emitter.send(SseEmitter.event().name("coordination_start").data(
                        Map.of("agentId", st.coordinator, "conversationId", conversationId),
                        MediaType.APPLICATION_JSON));
            }
            if (st.discussing) {
                emitter.send(SseEmitter.event().name("discussion_start").data(
                        Map.of("conversationId", conversationId), MediaType.APPLICATION_JSON));
            }
            for (Map.Entry<String, InFlightSegment> e : st.segments.entrySet()) {
                emitter.send(SseEmitter.event().name("reply_start").data(
                        Map.of("messageId", e.getKey(), "agentId", e.getValue().agentId,
                                "conversationId", conversationId),
                        MediaType.APPLICATION_JSON));
                String text = e.getValue().text.toString();
                if (!text.isEmpty()) {
                    emitter.send(SseEmitter.event().name("delta").data(
                            Map.of("messageId", e.getKey(), "delta", text, "replay", true,
                                    "conversationId", conversationId),
                            MediaType.APPLICATION_JSON));
                }
            }
        } catch (Exception ignored) {
            // 重连者立刻断开：onCompletion 会清理注册
        }
    }

    private void removeWatcher(String conversationId, SseEmitter emitter) {
        Set<SseEmitter> set = watchers.get(conversationId);
        if (set != null) {
            set.remove(emitter);
        }
    }

    /** 无响应客户端的扇出型 emitter：send 被拦截改道观察者，自身永不真正写流 */
    public static final class Broadcast extends SseEmitter {
        final String conversationId;

        public Broadcast(String conversationId) {
            super(0L);
            this.conversationId = conversationId;
        }
    }

    private void logEvent(String event, Object data) {
        String type = LOGGED_EVENTS.get(event);
        if (type == null || !(data instanceof Map<?, ?> payload)) {
            return;
        }
        appLogs.record(type, str(payload.get("conversationId")), str(payload.get("agentId")),
                event + (payload.get("agentName") == null ? "" : "「" + payload.get("agentName") + "」"));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** 生图进度 → SSE（image_start / image_end），普通回复与编排协作共用；会话 ID 由调用方给定（协作中可能切到项目群） */
    public ImageGenerationTools.ProgressListener imageListener(SseEmitter emitter, Supplier<String> conversationId,
                                                               Agent agent) {
        return new ImageGenerationTools.ProgressListener() {
            @Override
            public void onStart() {
                send(emitter, "image_start", Map.of(
                        "agentId", agent.getId(),
                        "agentName", agent.getName(),
                        "conversationId", conversationId.get()));
            }

            @Override
            public void onEnd() {
                send(emitter, "image_end", Map.of("conversationId", conversationId.get()));
            }
        };
    }

    /** 定时任务回合标注：随消息落库，前端据此显示来源任务徽标并支持按任务筛选；auto* 为该回合的审批放行策略；collab 表示协作任务（编排者必须委派成员，自己包揽不算实际执行） */
    public record TaskTag(String taskId, String taskName, boolean autoWrite, boolean autoShell, boolean collab) {
    }

    public Message saveMessage(Conversation conv, Agent agent, String content, String type) {
        return saveMessage(conv, agent, content, type, null);
    }

    public Message saveMessage(Conversation conv, Agent agent, String content, String type, TaskTag tag) {
        Message m = new Message();
        m.setId(Ids.next());
        m.setConversationId(conv.getId());
        m.setSenderType("agent");
        m.setSenderId(agent.getId());
        m.setContent(content);
        m.setType(type);
        if (tag != null) {
            m.setTaskId(tag.taskId());
            m.setTaskName(tag.taskName());
        }
        m.setCreatedAt(System.currentTimeMillis());
        return messages.save(m);
    }

    public void persist(Message m) {
        messages.save(m);
    }

    public void persistError(Conversation conv, Agent agent, String error) {
        saveMessage(conv, agent, error, "error");
        appLogs.record(AppLog.TYPE_ERROR, conv.getId(), agent.getId(), error);
    }

    public void markError(Message placeholder, String error) {
        placeholder.setType("error");
        placeholder.setContent(error);
        messages.save(placeholder);
        appLogs.record(AppLog.TYPE_ERROR, placeholder.getConversationId(), placeholder.getSenderId(), error);
    }

    public void touchConversation(Conversation conv, Agent agent, String content) {
        long now = System.currentTimeMillis();
        conv.setLastMessage("group".equals(conv.getType()) ? agent.getName() + ": " + content : content);
        conv.setLastMessageAt(now);
        conv.setUpdatedAt(now);
        conversations.save(conv);
    }

    public Optional<ModelPreset> presetOf(Agent a) {
        if (Str.isBlank(a.getPresetId())) {
            return Optional.empty();
        }
        return presets.findById(a.getPresetId());
    }

    /** 最近一条系统标注（如微信会话的处理智能体切换记录），无则返回空串 */
    public String latestSystemNote(String conversationId) {
        return messages.findFirstByConversationIdAndTypeOrderByCreatedAtDesc(conversationId, "system")
                .map(Message::getContent)
                .orElse("");
    }

    /** 会话文本历史转成 AgentScope 消息（新用户消息已包含在内）；用户消息中的图片附件转成 ImageBlock 供视觉模型查看 */
    public List<Msg> historyMsgs(String conversationId) {
        return historyMsgs(conversationId, null);
    }

    /**
     * 单聊输入（含接管分界）：会话存在系统标注（如微信通道切换处理智能体）时，在标注时间点
     * 插入一条合成用户消息，把之前的历史明确划给前任处理者。单聊历史不带署名，仅靠系统提示
     * 里的接管说明扛不住长历史的惯性——模型会把用户对前任说的话当成对自己说的。
     */
    public List<Msg> singleChatInput(String conversationId) {
        Message note = messages.findFirstByConversationIdAndTypeOrderByCreatedAtDesc(conversationId, "system")
                .orElse(null);
        if (note == null) {
            return historyMsgs(conversationId);
        }
        List<Message> scoped = scopedByBudget(conversationId, textMessages(conversationId));
        if (scoped.isEmpty() || scoped.get(0).getCreatedAt() >= note.getCreatedAt()) {
            // 预算/摘要水位线裁剪后已看不到前任时期的消息，无需分界
            return historyMsgs(conversationId);
        }
        List<Msg> out = new ArrayList<>();
        boolean marked = false;
        for (Message m : scoped) {
            if (!marked && m.getCreatedAt() >= note.getCreatedAt()) {
                out.add(takeoverMarker(note.getContent()));
                marked = true;
            }
            out.add(toHistoryMsg(m, false, null));
        }
        return out;
    }

    /** 接管分界合成消息：以用户角色插进历史流，模型在该位置读到"之前都是与前任的对话" */
    private static Msg takeoverMarker(String noteContent) {
        return new UserMessage(List.of(TextBlock.builder().text(
                "【系统标注】" + (noteContent == null ? "" : noteContent.trim())
                        + "。截止这条标注之前的全部历史，都是用户与前任处理者之间的对话："
                        + "智能体的回复是前任说的，不是你说的；用户当时的称呼、语气、情绪（无论亲密还是攻击性）也都是对前任发的，"
                        + "与你无关。不要把那段对话当成你的经历、记忆，或你们之间已有的关系。"
                        + "请从这条标注之后，以你自己的身份面对用户的最新消息。").build()));
    }

    /**
     * 带发送者名称的历史（agentNames 传 null 表示不署名，保持单聊原行为）：
     * 成员消息携带发言人名称、用户消息携带「用户」，配合 MultiAgentFormatter
     * 会被合并成带署名的 &lt;history&gt;，群聊成员能分清谁说了什么，
     * 而不是把其他成员的发言当成自己的历史。
     * 上下文压缩：水位线之前的消息已滚入摘要（digest 由调用方拼进 system prompt），
     * 注入时跳过；仍超字符预算的旧消息按预算从最新往回截断。
     */
    public List<Msg> historyMsgs(String conversationId, Map<String, String> agentNames) {
        boolean named = agentNames != null;
        return scopedByBudget(conversationId, textMessages(conversationId)).stream()
                .<Msg>map(m -> toHistoryMsg(m, named, agentNames))
                .toList();
    }

    /** 会话内可进模型历史的消息：仅文本类型，且正文或用户附件非空 */
    private List<Message> textMessages(String conversationId) {
        return messages.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .filter(m -> "text".equals(m.getType()))
                .filter(m -> hasContent(m) || ("user".equals(m.getSenderType()) && !Json.readAttachments(m.getAttachments()).isEmpty()))
                .toList();
    }

    private Msg toHistoryMsg(Message m, boolean named, Map<String, String> agentNames) {
        if ("user".equals(m.getSenderType())) {
            return userMsg(m, named ? "用户" : null);
        }
        return named
                ? AssistantMessage.builder()
                        .name(agentNames.getOrDefault(m.getSenderId(), "成员"))
                        .textContent(m.getContent())
                        .build()
                : new AssistantMessage(m.getContent());
    }

    /** 应用摘要水位线与字符预算：水位线之前的历史已滚入摘要；装不进预算的旧消息本轮截断 */
    private List<Message> scopedByBudget(String conversationId, List<Message> all) {
        ContextCompressionService.Settings s = compression.load();
        if (!s.enabled()) {
            return all;
        }
        String watermark = conversations.findById(conversationId)
                .map(conversation -> conversation.getDigestWatermark())
                .filter(w -> w != null && !w.isBlank())
                .orElse(null);
        List<Message> effective = ContextCompressionService.afterWatermark(all, watermark);
        long acc = 0;
        int from = effective.size();
        for (int i = effective.size() - 1; i >= 0; i--) {
            acc += ContextCompressionService.charsOf(effective.get(i));
            if (acc > s.budgetChars()) {
                break;
            }
            from = i;
        }
        // 最新一条（通常是本轮用户消息）无论多大都必须保留
        if (from >= effective.size() && !effective.isEmpty()) {
            from = effective.size() - 1;
        }
        return from == 0 ? effective : effective.subList(from, effective.size());
    }

    /**
     * 单条用户消息转多模态 Msg：文本注记 + 图片块；图片读取失败时自动退化为纯文本。
     * 已阅图片（consumed）不再注入视觉块——首轮已被模型看过，其回复即承载了图片信息，
     * 后续轮只保留 effectiveUserText 里的文字注记，需要重看时模型可调用 view_image 工具。
     */
    private UserMessage userMsg(Message m, String name) {
        List<ContentBlock> blocks = new ArrayList<>();
        String text = effectiveUserText(m);
        if (!text.isBlank()) {
            blocks.add(TextBlock.builder().text(text).build());
        }
        for (Json.Attachment a : Json.readAttachments(m.getAttachments())) {
            if (!ConversationFileGrant.TYPE_IMAGE.equals(a.type()) || a.consumed()) continue;
            ImageBlock img = imageBlock(Paths.get(a.path()));
            if (img != null) blocks.add(img);
        }
        return name == null ? new UserMessage(blocks) : UserMessage.builder().name(name).content(blocks).build();
    }

    private ImageBlock imageBlock(Path p) {
        return Images.toImageBlock(p, Images.MAX_IMAGE_BYTES);
    }

    /** 用户消息带上附件说明，模型才知道该条消息在指哪些文件/文件夹/图片 */
    private String effectiveUserText(Message m) {
        List<Json.Attachment> atts = Json.readAttachments(m.getAttachments());
        String content = m.getContent() == null ? "" : m.getContent();
        if (atts.isEmpty()) return content;
        StringBuilder sb = new StringBuilder(content);
        if (!content.isBlank()) sb.append("\n\n");
        sb.append("（本条消息附带了以下").append(atts.size() > 1 ? atts.size() + "项" : "").append("附件，文件/文件夹可用文件工具直接读写：");
        for (Json.Attachment a : atts) {
            String label = "dir".equals(a.type()) ? "[文件夹] "
                    : ConversationFileGrant.TYPE_IMAGE.equals(a.type())
                            ? (a.consumed() ? "[图片·已阅，如需重看可调用 view_image 工具] " : "[图片] ")
                            : "[文件] ";
            sb.append("\n- ").append(label).append(a.path());
        }
        sb.append("）");
        return sb.toString();
    }

    private boolean hasContent(Message m) {
        return m.getContent() != null && !m.getContent().isBlank();
    }


    /** 单次模型调用的超时上限，须小于各服务的整体运行上限（成员 4 分钟 / 编排 8 分钟） */
    public static final Duration MODEL_CALL_TIMEOUT = Duration.ofMinutes(3);

    /**
     * 单次模型调用的超时与重试策略（框架 ExecutionConfig）：
     * 不设置时框架对模型调用既无超时也无重试；设置后对可重试错误（传输故障、可重试 HTTP 状态、超时、IO）
     * 自动指数退避重试，单次调用超时以 ModelException 表达。整体运行上限仍由各服务的 blockLast 兜底。
     */
    public static ExecutionConfig modelCallExecutionConfig() {
        return ExecutionConfig.builder()
                .timeout(MODEL_CALL_TIMEOUT)
                .maxAttempts(2)
                .initialBackoff(Duration.ofSeconds(2))
                .maxBackoff(Duration.ofSeconds(15))
                .retryOn(ExecutionConfig.RETRYABLE_ERRORS)
                .build();
    }

    /** 整体运行上限的 blockLast 超时（reactor 特有，无类型化异常，只能按消息识别） */
    public static boolean isBlockingTimeout(Exception e) {
        return e instanceof IllegalStateException
                && String.valueOf(e.getMessage()).contains("Timeout on blocking read");
    }

    /** 用户已请求终止（stopRequested），或框架因 interrupt 抛出的中断错误（可能被包装在 cause 链里） */
    public static boolean isCancelSignal(Throwable e, AtomicBoolean stopRequested) {
        if (stopRequested.get()) return true;
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException) return true;
        }
        return false;
    }

    /** 一次回复中的一段发言：reply_start 到 reply_end 之间的增量文本；工具调用会把一次回复切成多段 */
    public static class SegState {
        public final AtomicReference<Message> msg = new AtomicReference<>();
        public final TextAccumulator text = new TextAccumulator();
        /** 已开启的段数（含进行中），用于判断整次回复是否完全空白；跨线程读写需 volatile */
        public volatile int opened;
    }

    public void openSegment(SseEmitter emitter, Conversation conv, Agent agent, SegState seg) {
        openSegment(emitter, conv, agent, seg, null);
    }

    public void openSegment(SseEmitter emitter, Conversation conv, Agent agent, SegState seg, TaskTag tag) {
        Message m = saveMessage(conv, agent, "", "text", tag);
        send(emitter, "reply_start", Map.of(
                "messageId", m.getId(),
                "agentId", agent.getId(),
                "conversationId", conv.getId()));
        seg.msg.set(m);
        seg.opened++;
    }

    public void closeSegment(SseEmitter emitter, Conversation conv, Agent agent, SegState seg) {
        Message m = seg.msg.getAndSet(null);
        if (m == null) return;
        String content = seg.text.getAccumulated();
        seg.text.reset();
        if (content.isBlank()) {
            markError(m, "（模型未返回内容）");
            send(emitter, "reply_error", Map.of(
                    "messageId", m.getId(),
                    "agentId", agent.getId(),
                    "error", "（模型未返回内容）",
                    "conversationId", conv.getId()));
            return;
        }
        m.setContent(content);
        persist(m);
        touchConversation(conv, agent, content);
        send(emitter, "reply_end", Map.of(
                "messageId", m.getId(),
                "agentId", agent.getId(),
                "content", content,
                "conversationId", conv.getId()));
    }
}
