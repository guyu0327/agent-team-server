package com.guyu.agentteam.service;

import com.guyu.agentteam.common.Str;
import com.guyu.agentteam.entity.AppLog;
import com.guyu.agentteam.entity.Agent;
import com.guyu.agentteam.entity.ModelPreset;
import com.guyu.agentteam.service.orchestration.AgentModelFactory;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * 提问工具（ask_user）的问答链路：智能体遇到高代价歧义时向前端发提问卡片并阻塞等用户作答，
 * 答案作为工具结果返回、回合无缝继续；「插入而不打断」——卡片是消息流的一部分，AI 的等待
 * 是它主动发起的，界面无任何中断感。
 * 每个问题独立发卡、独立计时（用户可能答到一半离开，已答的不作废）；用户点「停止」终止回合时
 * 由 stop() 调 {@link #cancelAllForConversation} 按终止唤醒，不白等。
 * 被委派成员的提问不直达用户：先经 {@link #proxyAnswer} 由编排者独立轻量调用代答，
 * 编排者也判断不了（escalate）才升级弹用户卡片。
 */
@Service
public class AskUserService {

    /** 单个问题等用户回答的上限：用户可能离开打字或想清楚，比审批的 120 秒长 */
    private static final Duration WAIT_TIMEOUT = Duration.ofMinutes(5);
    /** 编排者代答是短任务：超时按无法判断处理，直接升级问用户，不让成员白等 */
    private static final Duration PROXY_TIMEOUT = Duration.ofSeconds(60);

    private final ConversationStreamSupport support;
    private final AgentModelFactory modelFactory;
    private final AppLogService appLogs;

    private final Map<String, PendingAsk> pending = new ConcurrentHashMap<>();

    private static final class PendingAsk {
        final CompletableFuture<String> future = new CompletableFuture<>();
        final String conversationId;
        final String question;

        PendingAsk(String conversationId, String question) {
            this.conversationId = conversationId;
            this.question = question;
        }
    }

    /** 一次提问的结果：answer 是用户输入/编排者代答的文本，answered=false 表示超时或被终止 */
    public record AskOutcome(String answer, boolean answered, boolean fromProxy) {
        static AskOutcome ofUser(String answer) {
            return new AskOutcome(answer, true, false);
        }

        static AskOutcome ofProxy(String answer) {
            return new AskOutcome(answer, true, true);
        }

        static AskOutcome unanswered(String why) {
            return new AskOutcome(why, false, false);
        }
    }

    public AskUserService(ConversationStreamSupport support, AgentModelFactory modelFactory, AppLogService appLogs) {
        this.support = support;
        this.modelFactory = modelFactory;
        this.appLogs = appLogs;
    }

    /**
     * 逐个弹出问题并阻塞等待答案。一次调用带多个问题时逐个发卡（同一时刻消息流里只有一张 active 卡），
     * 前一个被回答（或超时）后才发下一个。
     */
    public List<AskOutcome> ask(SseEmitter emitter, Agent agent, String conversationId,
                                List<String> questions, List<List<String>> optionsList) {
        List<AskOutcome> outcomes = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            outcomes.add(askOne(emitter, agent, conversationId, questions.get(i),
                    i < optionsList.size() ? optionsList.get(i) : List.of()));
        }
        return outcomes;
    }

    private AskOutcome askOne(SseEmitter emitter, Agent agent, String conversationId,
                              String question, List<String> options) {
        String requestId = UUID.randomUUID().toString();
        PendingAsk pr = new PendingAsk(conversationId, question);
        pending.put(requestId, pr);
        try {
            support.send(emitter, "ask_user", Map.of(
                    "requestId", requestId,
                    "conversationId", conversationId,
                    "agentId", agent.getId(),
                    "agentName", agent.getName(),
                    "question", question,
                    "options", options));
            appLogs.record(AppLog.TYPE_ASK_USER, conversationId, agent.getId(),
                    "「" + agent.getName() + "」向用户提问：" + question);
            String answer;
            try {
                answer = pr.future.get(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                return AskOutcome.unanswered("用户暂未回答（等待 5 分钟超时）");
            } catch (Exception e) {
                return AskOutcome.unanswered("等待被中断");
            }
            if (answer == null) {
                // stop() 终止联动唤醒：回合已结束，不再等待
                return AskOutcome.unanswered("回合已被用户终止");
            }
            return AskOutcome.ofUser(answer);
        } finally {
            pending.remove(requestId);
        }
    }

    /** 提问卡片回答回调：answer 为所选项文本或「其他」自由输入。返回 false 表示问题已超时失效。 */
    public boolean answer(String requestId, String answer) {
        PendingAsk pr = pending.get(requestId);
        if (pr == null) {
            return false;
        }
        String text = answer == null ? "" : answer.trim();
        return pr.future.complete(text.isEmpty() ? "（用户没有输入内容）" : text);
    }

    /** 终止联动：该会话所有等待中的提问按终止唤醒（用户点「停止」时由 stop() 调用） */
    public void cancelAllForConversation(String conversationId) {
        for (Map.Entry<String, PendingAsk> e : pending.entrySet()) {
            PendingAsk pr = e.getValue();
            if (pr.conversationId.equals(conversationId)) {
                pr.future.complete(null);
            }
        }
    }

    /**
     * 编排者代答：成员执行中的疑问先由编排者以独立轻量调用判断（不进任何对话上下文）。
     * 返回 null 表示编排者也判断不了（escalate）或调用失败/超时——调用方升级弹用户卡片。
     */
    public String proxyAnswer(Agent orchestrator, ModelPreset preset, String conversationId,
                              String taskContext, String question) {
        String ask = "【任务背景】\n" + ellipsis(taskContext, 4000)
                + "\n\n【成员的问题】\n" + ellipsis(question, 1000)
                + "\n\n请基于任务背景直接回答成员的问题（answer 字段，要明确、可执行）；"
                + "若你判断这个问题必须由用户本人决定（需求方向、个人偏好、预算等），answer 留空并在 escalate 填 true。";
        try (ReActAgent judge = ReActAgent.builder()
                .name("orchestrator-proxy")
                .sysPrompt("你是团队编排者。你委派的成员在执行任务中遇到疑问来问你，请站在整体任务与用户需求的角度"
                        + "替用户给出判断；只有当该问题确实必须由用户本人拍板时才升级转问用户。")
                .model(modelFactory.create(orchestrator, preset, false))
                .build()) {
            Msg decision = judge.call(List.of(new UserMessage(List.of(TextBlock.builder().text(ask).build()))),
                    ProxyAnswer.class).block(PROXY_TIMEOUT);
            ProxyAnswer a = decision == null ? null : decision.getStructuredData(ProxyAnswer.class);
            if (a == null || a.isEscalate() || Str.isBlank(a.getAnswer())) {
                return null;
            }
            return a.getAnswer().trim();
        } catch (Exception e) {
            appLogs.record(AppLog.TYPE_ASK_USER, conversationId, orchestrator.getId(),
                    "编排者「" + orchestrator.getName() + "」代答失败（"
                            + ellipsis(e.getMessage() == null ? e.toString() : e.getMessage(), 200) + "），转问用户");
            return null;
        }
    }

    /** 编排者代答的结构化输出：answer=代答内容（空=无法判断），escalate=true 表示须由用户本人决定 */
    public static class ProxyAnswer {
        private String answer;
        private boolean escalate;

        public String getAnswer() {
            return answer;
        }

        public void setAnswer(String answer) {
            this.answer = answer;
        }

        public boolean isEscalate() {
            return escalate;
        }

        public void setEscalate(boolean escalate) {
            this.escalate = escalate;
        }
    }

    private static String ellipsis(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
