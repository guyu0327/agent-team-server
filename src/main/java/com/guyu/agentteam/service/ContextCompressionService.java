package com.guyu.agentteam.service;

import com.guyu.agentteam.common.ApiException;
import com.guyu.agentteam.common.Json;
import com.guyu.agentteam.dto.ContextCompressionDto;
import com.guyu.agentteam.entity.Agent;
import com.guyu.agentteam.entity.AppLog;
import com.guyu.agentteam.entity.Conversation;
import com.guyu.agentteam.entity.ConversationFileGrant;
import com.guyu.agentteam.entity.ConversationMember;
import com.guyu.agentteam.entity.Message;
import com.guyu.agentteam.entity.ModelPreset;
import com.guyu.agentteam.repository.AgentRepository;
import com.guyu.agentteam.repository.ConversationMemberRepository;
import com.guyu.agentteam.repository.ConversationRepository;
import com.guyu.agentteam.repository.MessageRepository;
import com.guyu.agentteam.repository.ModelPresetRepository;
import com.guyu.agentteam.service.orchestration.AgentModelFactory;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 上下文压缩：控制每轮注入模型的历史规模，两层机制。
 * 消息层（图片已阅）：用户消息里的图片在本回合被模型看过后标记 consumed，
 * 后续轮次只保留文字注记（需要重看可用 view_image 工具按需重注入）。
 * 历史层（滚动摘要）：注入字符超过预算时，把水位线之后较旧的一段消息连同旧摘要
 * 一起用会话模型压缩成新摘要（digest，拼进 system prompt），水位线随之推进；
 * 压缩同步显性（compress_start/end 事件，前端气泡下方显示「正在压缩」），
 * 失败降级为纯截断（historyMsgs 按预算从最新往回取）并进入暂缓期，恢复后自动重试。
 */
@Service
public class ContextCompressionService {

    public static final boolean DEFAULT_ENABLED = true;
    public static final int DEFAULT_BUDGET_CHARS = 50000;
    /** 预算上下限（字符）：设置页以 K 为单位输入，范围 20K-500K */
    private static final int BUDGET_MIN = 20000;
    private static final int BUDGET_MAX = 500000;
    private static final String KEY = "context.compression";
    /** 触发压缩时保留原文的近期消息字符预算（预算的一半），其余较旧部分滚入摘要 */
    private static final double SUFFIX_KEEP_RATIO = 0.5;
    /** 压缩触发线：注入总量（含系统提示词等不可压缩项）达到预算的 90% 即后台压缩 */
    private static final double COMPACT_TRIGGER_RATIO = 0.9;
    /** 摘要本身的长度上限（字符）：约预算的 1/8，封顶 8000 */
    private static final int DIGEST_MAX = 8000;
    /**
     * 摘要调用的总时长上限：须盖过 modelCallExecutionConfig 的完整重试链
     * （3 分钟空闲超时 × 2 次尝试 + 指数退避间隔），否则外层先到期，配置的重试永远轮不到发挥。
     */
    private static final Duration SUMMARIZE_TIMEOUT = ConversationStreamSupport.MODEL_CALL_TIMEOUT
            .multipliedBy(2).plus(Duration.ofSeconds(30));
    /** 摘要失败后的暂缓期：期间回合直接按预算截断历史（毫秒级），不再每轮白等一次完整重试 */
    private static final long COOLDOWN_MS = Duration.ofMinutes(10).toMillis();
    /** conversationId -> 摘要失败后暂缓压缩的截止时间（内存态，重启即清零重新尝试） */
    private final Map<String, Long> compressCooldownUntil = new ConcurrentHashMap<>();

    private final SettingsStore store;
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final ConversationMemberRepository members;
    private final AgentRepository agents;
    private final ModelPresetRepository presets;
    private final AgentModelFactory modelFactory;
    private final AppLogService appLogs;
    private final ObjectMapper mapper = Json.mapper();

    public ContextCompressionService(SettingsStore store, ConversationRepository conversations,
                                     MessageRepository messages, ConversationMemberRepository members,
                                     AgentRepository agents, ModelPresetRepository presets,
                                     AgentModelFactory modelFactory, AppLogService appLogs) {
        this.store = store;
        this.conversations = conversations;
        this.messages = messages;
        this.members = members;
        this.agents = agents;
        this.presets = presets;
        this.modelFactory = modelFactory;
        this.appLogs = appLogs;
    }

    public record Settings(boolean enabled, int budgetChars) {
    }

    public ContextCompressionDto get() {
        Settings s = load();
        return new ContextCompressionDto(s.enabled(), s.budgetChars());
    }

    public ContextCompressionDto save(boolean enabled, int budgetChars) {
        if (budgetChars < BUDGET_MIN || budgetChars > BUDGET_MAX) {
            throw new ApiException(400, "上下文预算需在 " + (BUDGET_MIN / 1000) + "K-" + (BUDGET_MAX / 1000) + "K 字符之间");
        }
        String json;
        try {
            json = mapper.writeValueAsString(new Settings(enabled, budgetChars));
        } catch (Exception e) {
            throw new ApiException(500, "保存上下文压缩设置失败");
        }
        store.write(KEY, json);
        return new ContextCompressionDto(enabled, budgetChars);
    }

    public Settings load() {
        String json = store.read(KEY);
        if (json == null) {
            return new Settings(DEFAULT_ENABLED, DEFAULT_BUDGET_CHARS);
        }
        try {
            Settings raw = mapper.readValue(json, Settings.class);
            return new Settings(raw.enabled(), clamp(raw.budgetChars()));
        } catch (Exception e) {
            return new Settings(DEFAULT_ENABLED, DEFAULT_BUDGET_CHARS);
        }
    }

    private int clamp(int v) {
        return Math.max(BUDGET_MIN, Math.min(v, BUDGET_MAX));
    }

    /** 会话的滚动摘要文本（systemPrompt 拼接用），无摘要返回 null */
    public String digestOf(String conversationId) {
        return conversations.findById(conversationId)
                .map(Conversation::getContextDigest)
                .filter(d -> d != null && !d.isBlank())
                .orElse(null);
    }

    /** 会话摘要的完整提示词块（含标题与 view_image 重看提示），无摘要返回 null；单聊/群聊与编排者拼装共用同一文案 */
    public String promptBlock(String conversationId) {
        String digest = digestOf(conversationId);
        if (digest == null || digest.isBlank()) {
            return null;
        }
        return "【会话早期历史摘要】\n更早的完整对话已压缩为以下要点，请以此作为早期上下文"
                + "（更早已阅的图片不再重复附上，需要重看可调用 view_image 工具）：\n" + digest.trim();
    }

    /**
     * 是否需要压缩：已启用且不在失败暂缓期、模型预设可用，且总注入量达到预算的 90%。
     * totalChars 是「系统提示词 + 拼装输入」的真实字符量（与指示条同一口径——含署名、
     * QQ 发送者前缀、【系统行动记录】、附件完整注记等拼装文本；raw 正文粗估会少算这些，
     * 出现指示条 90% 了后端还没到线的错位），且须有可滚入摘要的旧消息
     * （单条消息就超出保留预算时只交截断兜底）。
     * 显性化提示（compress_start 是否该发）与压缩本体共用同一判定，两边口径永不打架。
     */
    public boolean needsCompact(Conversation conv, Agent modelOwner, long totalChars) {
        if (modelOwner == null || !load().enabled()) {
            return false;
        }
        Long until = compressCooldownUntil.get(conv.getId());
        if (until != null && System.currentTimeMillis() < until) {
            return false;
        }
        if (presetOf(modelOwner).isEmpty()) {
            return false;
        }
        Settings s = load();
        if (totalChars < (long) (s.budgetChars() * COMPACT_TRIGGER_RATIO)) {
            return false;
        }
        List<Message> effective = afterWatermark(textMessages(conv.getId()), conv.getDigestWatermark());
        return suffixStartOf(effective, s.budgetChars()) > 0;
    }

    /**
     * 达到触发线时由后台压缩线程调用：把较旧的一段消息滚入摘要（一次摘要模型调用，
     * 不阻塞回合——回复按预算截断历史先行）。
     * modelOwner 提供压缩用的模型预设；失败记日志、进入暂缓期并降级为截断，后续轮照常回复。
     * 返回是否成功写入了新摘要（显性化据此告知前端成败）。
     */
    public boolean compactIfNeeded(Conversation conv, Agent modelOwner, long totalChars) {
        if (!needsCompact(conv, modelOwner, totalChars)) {
            return false;
        }
        Settings s = load();
        List<Message> effective = afterWatermark(textMessages(conv.getId()), conv.getDigestWatermark());
        int suffixStart = suffixStartOf(effective, s.budgetChars());
        List<Message> overflow = new ArrayList<>(effective.subList(0, suffixStart));
        String digest = summarize(conv, modelOwner, presetOf(modelOwner).get(), overflow);
        if (digest == null) {
            // 失败暂缓：期间回合直接走预算截断（毫秒级），不再每轮白等一次完整的摘要重试
            compressCooldownUntil.put(conv.getId(), System.currentTimeMillis() + COOLDOWN_MS);
            return false;
        }
        conv.setContextDigest(digest);
        conv.setDigestWatermark(overflow.get(overflow.size() - 1).getId());
        conv.setUpdatedAt(System.currentTimeMillis());
        conversations.save(conv);
        lastCompactErrors.remove(conv.getId());
        appLogs.record(AppLog.TYPE_COMPACT, conv.getId(), modelOwner.getId(),
                "上下文已压缩：" + overflow.size() + " 条消息滚入摘要（摘要 " + digest.length() + " 字），"
                        + "近 " + (effective.size() - suffixStart) + " 条保留原文");
        return true;
    }

    /** 各会话最近一次压缩失败的原因（成功即清除）：compress_end 带给前端，点击叹号可看 */
    private final Map<String, String> lastCompactErrors = new ConcurrentHashMap<>();

    /** 会话最近一次压缩失败的原因，无失败记录返回 null */
    public String lastCompactError(String conversationId) {
        return lastCompactErrors.get(conversationId);
    }

    /** 记录会话最近一次压缩失败原因（前端点击叹号展示；完整原因已在运行日志，这里留可读摘要） */
    private void recordCompactError(String conversationId, String reason) {
        String t = reason == null ? "未知原因" : reason.strip();
        lastCompactErrors.put(conversationId, t.length() > 500 ? t.substring(0, 500) + "…" : t);
    }

    /**
     * 溢出/保留的分界下标：从最新往回累计到保留预算（预算的一半）为止，
     * 其余较旧部分滚入摘要；最新一条永远保留原文（连它都超出保留预算时只压缩它之前的）。
     */
    private static int suffixStartOf(List<Message> effective, int budgetChars) {
        long keepBudget = (long) (budgetChars * SUFFIX_KEEP_RATIO);
        int suffixStart = effective.size();
        long acc = 0;
        for (int i = effective.size() - 1; i >= 0; i--) {
            acc += charsOf(effective.get(i));
            if (acc > keepBudget) {
                break;
            }
            suffixStart = i;
        }
        if (suffixStart >= effective.size() && !effective.isEmpty()) {
            suffixStart = effective.size() - 1;
        }
        return suffixStart;
    }

    /** 回合成功结束后调用：把会话内所有未阅的图片附件标记为已阅，后续轮次不再注入视觉块 */
    public void markImagesConsumed(String conversationId) {
        for (Message m : messages.findWithAttachmentsByConversationId(conversationId)) {
            List<Json.Attachment> atts = Json.readAttachments(m.getAttachments());
            if (atts.isEmpty()) {
                continue;
            }
            List<Json.Attachment> updated = new ArrayList<>(atts.size());
            boolean changed = false;
            for (Json.Attachment a : atts) {
                if (ConversationFileGrant.TYPE_IMAGE.equals(a.type()) && !a.consumed()) {
                    updated.add(new Json.Attachment(a.path(), a.type(), a.name(), true));
                    changed = true;
                } else {
                    updated.add(a);
                }
            }
            if (changed) {
                m.setAttachments(Json.writeAttachments(updated));
                messages.save(m);
            }
        }
    }

    /** 把溢出消息连同旧摘要压缩成一份完整新摘要；失败返回 null（已记日志） */
    private String summarize(Conversation conv, Agent modelOwner, ModelPreset preset, List<Message> overflow) {
        String oldDigest = conv.getContextDigest() == null ? "" : conv.getContextDigest().trim();
        StringBuilder input = new StringBuilder();
        if (!oldDigest.isBlank()) {
            input.append("【已有摘要（早期历史）】\n").append(oldDigest).append("\n\n");
        }
        input.append("【新增对话记录（较旧的一段）】\n").append(renderTranscript(conv.getId(), overflow));
        try (ReActAgent summarizer = ReActAgent.builder()
                .name("context-summarizer")
                .sysPrompt(COMPACTION_PROMPT)
                .model(modelFactory.create(modelOwner, preset))
                .modelExecutionConfig(ConversationStreamSupport.modelCallExecutionConfig())
                .build()) {
            // 纯文本调用，不走结构化输出：部分服务商/推理模型对「工具式结构化返回」支持不稳，
            // 模型写出摘要正文却不调工具，ReAct 循环空转到超时（曾 4 分钟静默失败）。本任务
            // 输入即全文输出，无需结构约束
            Msg out = summarizer.call(List.of(UserMessage.builder().textContent(input.toString()).build()))
                    .block(SUMMARIZE_TIMEOUT);
            String digest = out == null ? null : out.getTextContent();
            if (digest == null || digest.isBlank()) {
                String reason = "摘要调用无文本返回（输入 " + input.length()
                        + " 字，超时上限 " + SUMMARIZE_TIMEOUT.toMinutes() + " 分钟）";
                appLogs.record(AppLog.TYPE_ERROR, conv.getId(), modelOwner.getId(),
                        "上下文压缩失败，本轮按预算截断历史：" + reason);
                recordCompactError(conv.getId(), reason);
                return null;
            }
            digest = stripDigest(digest);
            return digest.length() > DIGEST_MAX ? digest.substring(0, DIGEST_MAX) + "…（摘要过长已截断）" : digest;
        } catch (Exception e) {
            String reason = ConversationStreamSupport.errorDetail(e);
            appLogs.record(AppLog.TYPE_ERROR, conv.getId(), modelOwner.getId(),
                    "上下文压缩失败，本轮按预算截断历史：" + reason);
            recordCompactError(conv.getId(), reason);
            return null;
        }
    }

    /** 摘要文本清理：剥掉 markdown 围栏与首尾空白（模型偶尔用代码块包裹输出） */
    private static String stripDigest(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            int first = t.indexOf('\n');
            if (first > 0) {
                t = t.substring(first + 1);
            }
            int last = t.lastIndexOf("```");
            if (last >= 0) {
                t = t.substring(0, last);
            }
            t = t.trim();
        }
        return t;
    }

    private static final String COMPACTION_PROMPT = """
            你是会话记录压缩器。你会收到一段聊天记录（可能还附有一份早期历史的旧摘要），\
            请把它们合并压缩成一份完整的摘要，供智能体作为该会话早期上下文使用。

            要求：
            1. 每条要点必须保留发言人（如「用户」「张三」），群聊里成员的发言绝不能混淆归属。
            2. 必须保留：用户的目标与偏好、已做出的决定、承诺要做的事、提到的文件路径与关键数据、
               未完成的任务和待决问题、与图片相关的关键结论。
            3. 区分「说过」与「做过」：发言可能附带「（实际执行：…）」标注，这是系统核实的执行凭证，\
               摘要必须如实保留（谁执行了什么）；没有标注的完成式表述只是口头自述，摘要里不得写成已完成。
            4. 舍弃：寒暄、重复表述、与任务无关的闲聊。
            5. 按主题或时间顺序组织，直接输出摘要正文（可用短行/短列表），不要添加评论或解释，
               不要用代码块包裹，不要输出摘要以外的任何内容。
            """;

    /** 消息列表转纯文本记录（署名 + 附件注记），供压缩模型阅读 */
    private String renderTranscript(String conversationId, List<Message> msgs) {
        Map<String, String> names = memberNames(conversationId);
        StringBuilder sb = new StringBuilder();
        for (Message m : msgs) {
            String speaker = "user".equals(m.getSenderType()) ? "用户"
                    : names.getOrDefault(m.getSenderId(), "成员");
            sb.append(speaker).append("：");
            String content = m.getContent() == null ? "" : m.getContent();
            sb.append(content.strip());
            for (Json.Attachment a : Json.readAttachments(m.getAttachments())) {
                String label = ConversationFileGrant.TYPE_DIR.equals(a.type()) ? "文件夹"
                        : ConversationFileGrant.TYPE_IMAGE.equals(a.type()) ? "图片" : "文件";
                sb.append(content.isBlank() ? "" : " ").append("（附件·").append(label).append("：").append(a.path()).append("）");
            }
            // 执行凭证随转写进摘要输入：摘要据此保留「哪些操作真实执行过」，说过没做的不会被写成已做
            sb.append(ConversationStreamSupport.execNoteOf(m));
            sb.append("\n");
        }
        return sb.toString();
    }

    private List<Message> textMessages(String conversationId) {
        return messages.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .filter(m -> "text".equals(m.getType()))
                .toList();
    }

    /** 返回水位线之后（不含水位线那条）的消息；水位线找不到（已被物理删除）则全量返回 */
    public static List<Message> afterWatermark(List<Message> all, String watermark) {
        if (watermark == null || watermark.isBlank()) {
            return all;
        }
        for (int i = 0; i < all.size(); i++) {
            if (watermark.equals(all.get(i).getId())) {
                return all.subList(i + 1, all.size());
            }
        }
        return all;
    }

    private long charsOf(List<Message> msgs) {
        long n = 0;
        for (Message m : msgs) {
            n += charsOf(m);
        }
        return n;
    }

    /** 单条消息的注入字符估算（附件按每条 100 字符粗略计），预算截断与压缩触发共用同一口径 */
    public static long charsOf(Message m) {
        long n = m.getContent() == null ? 0 : m.getContent().length();
        // 附件在 effectiveUserText 里会展开成注记文本，按每条 100 字符粗略估算
        return n + 100L * Json.readAttachments(m.getAttachments()).size();
    }

    /** 群成员 ID → 名称（历史署名与压缩记录共用） */
    public Map<String, String> memberNames(String conversationId) {
        return members.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .map(ConversationMember::getAgentId)
                .map(agents::findById)
                .flatMap(Optional::stream)
                .collect(Collectors.toMap(Agent::getId, Agent::getName, (a, b) -> a));
    }

    private Optional<ModelPreset> presetOf(Agent a) {
        if (a.getPresetId() == null || a.getPresetId().isBlank()) {
            return Optional.empty();
        }
        return presets.findById(a.getPresetId());
    }
}
