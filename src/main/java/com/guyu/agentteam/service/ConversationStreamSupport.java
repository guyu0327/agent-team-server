package com.guyu.agentteam.service;

import com.guyu.agentteam.common.Ids;
import com.guyu.agentteam.common.Json;
import com.guyu.agentteam.entity.Agent;
import com.guyu.agentteam.entity.Conversation;
import com.guyu.agentteam.entity.Message;
import com.guyu.agentteam.entity.ModelPreset;
import com.guyu.agentteam.repository.ConversationRepository;
import com.guyu.agentteam.repository.MessageRepository;
import com.guyu.agentteam.repository.ModelPresetRepository;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** 普通回复与编排回复共用的 SSE 发送和消息持久化逻辑 */
@Service
public class ConversationStreamSupport {

    private final ObjectMapper mapper = new ObjectMapper();

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final ModelPresetRepository presets;

    public ConversationStreamSupport(ConversationRepository conversations, MessageRepository messages,
                                     ModelPresetRepository presets) {
        this.conversations = conversations;
        this.messages = messages;
        this.presets = presets;
    }

    public void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event)
                    .data(mapper.writeValueAsString(data), MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Message saveMessage(Conversation conv, Agent agent, String content, String type) {
        Message m = new Message();
        m.setId(Ids.next());
        m.setConversationId(conv.getId());
        m.setSenderType("agent");
        m.setSenderId(agent.getId());
        m.setContent(content);
        m.setType(type);
        m.setCreatedAt(System.currentTimeMillis());
        return messages.save(m);
    }

    public void persist(Message m) {
        messages.save(m);
    }

    public void persistError(Conversation conv, Agent agent, String error) {
        saveMessage(conv, agent, error, "error");
    }

    public void markError(Message placeholder, String error) {
        placeholder.setType("error");
        placeholder.setContent(error);
        messages.save(placeholder);
    }

    public void touchConversation(Conversation conv, Agent agent, String content) {
        long now = System.currentTimeMillis();
        conv.setLastMessage("group".equals(conv.getType()) ? agent.getName() + ": " + content : content);
        conv.setLastMessageAt(now);
        conv.setUpdatedAt(now);
        conversations.save(conv);
    }

    public Optional<ModelPreset> presetOf(Agent a) {
        if (isBlank(a.getPresetId())) {
            return Optional.empty();
        }
        return presets.findById(a.getPresetId());
    }

    /** 会话文本历史转成 AgentScope 消息（新用户消息已包含在内） */
    public List<Msg> historyMsgs(String conversationId) {
        return messages.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .filter(m -> "text".equals(m.getType()))
                .filter(m -> hasContent(m) || ("user".equals(m.getSenderType()) && !Json.readAttachments(m.getAttachments()).isEmpty()))
                .<Msg>map(m -> "user".equals(m.getSenderType())
                        ? new UserMessage(effectiveUserText(m))
                        : new AssistantMessage(m.getContent()))
                .toList();
    }

    /** 会话内全部有内容的文本消息（按时间先后），供转写成讨论记录 */
    public List<Message> recentTextMessages(String conversationId) {
        return messages.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .filter(m -> "text".equals(m.getType()))
                .filter(this::hasContent)
                .toList();
    }

    /** 转写用文本：用户消息保留附件注记，成员消息用原始内容 */
    public String transcriptText(Message m) {
        return "user".equals(m.getSenderType()) ? effectiveUserText(m) : m.getContent();
    }

    /** 用户消息带上附件说明，模型才知道该条消息在指哪些文件/文件夹 */
    private String effectiveUserText(Message m) {
        List<Json.Attachment> atts = Json.readAttachments(m.getAttachments());
        String content = m.getContent() == null ? "" : m.getContent();
        if (atts.isEmpty()) return content;
        StringBuilder sb = new StringBuilder(content);
        if (!content.isBlank()) sb.append("\n\n");
        sb.append("（本条消息附带了以下").append(atts.size() > 1 ? atts.size() + "项" : "").append("文件/文件夹，可用文件工具直接读写：");
        for (Json.Attachment a : atts) {
            sb.append("\n- ").append("dir".equals(a.type()) ? "[文件夹] " : "[文件] ").append(a.path());
        }
        sb.append("）");
        return sb.toString();
    }

    private boolean hasContent(Message m) {
        return m.getContent() != null && !m.getContent().isBlank();
    }

    public boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** reactor 的 block* 超时以 IllegalStateException 表达 */
    public static boolean isBlockingTimeout(Exception e) {
        return e instanceof IllegalStateException
                && String.valueOf(e.getMessage()).contains("Timeout on blocking read");
    }

    /** 一次回复中的一段发言：reply_start 到 reply_end 之间的增量文本；工具调用会把一次回复切成多段 */
    public static class SegState {
        public final AtomicReference<Message> msg = new AtomicReference<>();
        public final StringBuilder text = new StringBuilder();
        /** 已开启的段数（含进行中），用于判断整次回复是否完全空白 */
        public int opened;
    }

    public void openSegment(SseEmitter emitter, Conversation conv, Agent agent, SegState seg) {
        Message m = saveMessage(conv, agent, "", "text");
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
        String content = seg.text.toString();
        seg.text.setLength(0);
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
