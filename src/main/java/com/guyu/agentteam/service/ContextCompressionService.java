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
import java.util.stream.Collectors;

/**
 * 上下文压缩：控制每轮注入模型的历史规模，两层机制。
 * 消息层（图片已阅）：用户消息里的图片在本回合被模型看过后标记 consumed，
 * 后续轮次只保留文字注记（需要重看可用 view_image 工具按需重注入）。
 * 历史层（滚动摘要）：注入字符超过预算时，把水位线之后较旧的一段消息连同旧摘要
 * 一起用会话模型压缩成新摘要（digest，拼进 system prompt），水位线随之推进；
 * 摘要失败时降级为纯截断（historyMsgs 按预算从最新往回取），不阻塞回复。
 */
@Service
public class ContextCompressionService {

    public static final boolean DEFAULT_ENABLED = true;
    public static final int DEFAULT_BUDGET_CHARS = 60000;
    private static final int BUDGET_MIN = 5000;
    private static final int BUDGET_MAX = 500000;
    private static final String KEY = "context.compression";
    /** 触发压缩时保留原文的近期消息字符预算（预算的一半），其余较旧部分滚入摘要 */
    private static final double SUFFIX_KEEP_RATIO = 0.5;
    /** 摘要本身的长度上限（字符）：约预算的 1/8，封顶 8000 */
    private static final int DIGEST_MAX = 8000;
    private static final Duration SUMMARIZE_TIMEOUT = Duration.ofMinutes(2);

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

    /** 压缩模型的结构化输出：digest 为合并旧摘要后的完整新摘要 */
    public static class DigestOut {
        private String digest;

        public String getDigest() {
            return digest;
        }

        public void setDigest(String digest) {
            this.digest = digest;
        }
    }

    public ContextCompressionDto get() {
        Settings s = load();
        return new ContextCompressionDto(s.enabled(), s.budgetChars());
    }

    public ContextCompressionDto save(boolean enabled, int budgetChars) {
        if (budgetChars < BUDGET_MIN || budgetChars > BUDGET_MAX) {
            throw new ApiException(400, "上下文预算需在 " + BUDGET_MIN + "-" + BUDGET_MAX + " 字符之间");
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

    /**
     * 回合开始前调用：超过预算则把较旧的一段消息滚入摘要（同步一次模型调用）。
     * modelOwner 提供压缩用的模型预设；失败只记日志并降级为截断，绝不阻塞回复。
     */
    public void compactIfNeeded(Conversation conv, Agent modelOwner) {
        if (modelOwner == null || !load().enabled()) {
            return;
        }
        Optional<ModelPreset> presetOpt = presetOf(modelOwner);
        if (presetOpt.isEmpty()) {
            return;
        }
        Settings s = load();
        List<Message> all = textMessages(conv.getId());
        List<Message> effective = afterWatermark(all, conv.getDigestWatermark());
        long digestChars = conv.getContextDigest() == null ? 0 : conv.getContextDigest().length();
        if (charsOf(effective) + digestChars <= s.budgetChars()) {
            return;
        }
        long keepBudget = (long) (s.budgetChars() * SUFFIX_KEEP_RATIO);
        int suffixStart = effective.size();
        long acc = 0;
        for (int i = effective.size() - 1; i >= 0; i--) {
            acc += charsOf(effective.get(i));
            if (acc > keepBudget) {
                break;
            }
            suffixStart = i;
        }
        // 最新一条永远保留原文（连它都超出保留预算时只压缩它之前的）
        if (suffixStart >= effective.size() && !effective.isEmpty()) {
            suffixStart = effective.size() - 1;
        }
        // 没有任何可压缩的旧消息（单条消息就超出保留预算），交给 historyMsgs 的预算截断兜底
        if (suffixStart == 0) {
            return;
        }
        List<Message> overflow = new ArrayList<>(effective.subList(0, suffixStart));
        String digest = summarize(conv, modelOwner, presetOpt.get(), overflow);
        if (digest == null) {
            return;
        }
        conv.setContextDigest(digest);
        conv.setDigestWatermark(overflow.get(overflow.size() - 1).getId());
        conv.setUpdatedAt(System.currentTimeMillis());
        conversations.save(conv);
        appLogs.record(AppLog.TYPE_COMPACT, conv.getId(), modelOwner.getId(),
                "上下文已压缩：" + overflow.size() + " 条消息滚入摘要（摘要 " + digest.length() + " 字），"
                        + "近 " + (effective.size() - suffixStart) + " 条保留原文");
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
            Msg out = summarizer.call(List.of(UserMessage.builder().textContent(input.toString()).build()), DigestOut.class)
                    .block(SUMMARIZE_TIMEOUT);
            if (out == null || !out.hasStructuredData()) {
                return null;
            }
            String digest = out.getStructuredData(DigestOut.class).getDigest();
            if (digest == null || digest.isBlank()) {
                return null;
            }
            digest = digest.trim();
            return digest.length() > DIGEST_MAX ? digest.substring(0, DIGEST_MAX) + "…（摘要过长已截断）" : digest;
        } catch (Exception e) {
            String err = e.getMessage() == null ? e.toString() : e.getMessage();
            appLogs.record(AppLog.TYPE_ERROR, conv.getId(), modelOwner.getId(),
                    "上下文压缩失败，本轮按预算截断历史：" + err);
            return null;
        }
    }

    private static final String COMPACTION_PROMPT = """
            你是会话记录压缩器。你会收到一段聊天记录（可能还附有一份早期历史的旧摘要），\
            请把它们合并压缩成一份完整的摘要，供智能体作为该会话早期上下文使用。

            要求：
            1. 每条要点必须保留发言人（如「用户」「张三」），群聊里成员的发言绝不能混淆归属。
            2. 必须保留：用户的目标与偏好、已做出的决定、承诺要做的事、提到的文件路径与关键数据、
               未完成的任务和待决问题、与图片相关的关键结论。
            3. 舍弃：寒暄、重复表述、与任务无关的闲聊。
            4. 按主题或时间顺序组织，输出纯文本（可用短行/短列表），不要添加评论或解释。
            5. 把结果填在 digest 字段返回。
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
