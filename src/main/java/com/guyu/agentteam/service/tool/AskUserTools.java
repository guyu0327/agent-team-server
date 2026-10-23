package com.guyu.agentteam.service.tool;

import com.guyu.agentteam.entity.Agent;
import com.guyu.agentteam.service.AskUserService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 提问工具：智能体遇到高代价歧义时把需要用户拍板的问题集中一次问清。
 * 一次调用可带多个问题，逐个弹卡（答完一个弹下一个）；每个问题最多 3 个候选选项 + 「其他」自由输入，
 * 选项形式不限（取舍/完整方案 vs 降级方案/不同路线皆可）。
 * proxy 非空表示被委派成员链路：问题先由编排者代答，编排者也判断不了才升级弹用户卡片。
 */
@Service
public class AskUserTools {

    /** 单次调用的问题数上限，超出截断（太多了说明任务理解没做，不该靠问用户兜底） */
    private static final int MAX_QUESTIONS = 5;
    private static final int MAX_OPTIONS = 3;

    private final AskUserService askUsers;

    public AskUserTools(AskUserService askUsers) {
        this.askUsers = askUsers;
    }

    /**
     * 注册 ask_user 工具。proxy 非空时先经编排者代答（被委派成员链路，proxyName 为编排者名）；
     * note 非空时把「成员提问 → 代答/转问用户」的过程写进协作群留痕（系统注记）。
     */
    public void register(Toolkit toolkit, SseEmitter emitter, Agent agent, Supplier<String> conversationId,
                         Function<String, String> proxy, Supplier<String> proxyName, Consumer<String> note) {
        toolkit.registerTool(new Instance(askUsers, emitter, agent, conversationId, proxy, proxyName, note));
    }

    private static class Instance {

        private final AskUserService askUsers;
        private final SseEmitter emitter;
        private final Agent agent;
        private final Supplier<String> conversationId;
        /** 非空=成员链路：question → 编排者代答文本；null 表示编排者也判断不了，升级问用户 */
        private final Function<String, String> proxy;
        /** 成员链路的编排者名（结果文案与留痕里区分答案来源） */
        private final Supplier<String> proxyName;
        /** 非空=成员链路留痕：把提问与答案来源写进协作群 */
        private final Consumer<String> note;

        Instance(AskUserService askUsers, SseEmitter emitter, Agent agent, Supplier<String> conversationId,
                 Function<String, String> proxy, Supplier<String> proxyName, Consumer<String> note) {
            this.askUsers = askUsers;
            this.emitter = emitter;
            this.agent = agent;
            this.conversationId = conversationId;
            this.proxy = proxy;
            this.proxyName = proxyName;
            this.note = note;
        }

        @Tool(name = "ask_user", description = "Ask the user to make decisions when requirements are ambiguous "
                + "or several plausible interpretations exist and acting on a wrong guess is costly. Put ALL "
                + "questions you have into ONE call (1-5 questions); cards are shown one by one — the next "
                + "appears after the previous is answered. Each question carries up to 3 candidate answers in "
                + "any form: either-or tradeoffs, full plan vs downgraded plan, different directions, etc.; "
                + "the user can always pick 'other' and type freely, so do NOT add an 'other' option yourself. "
                + "Do NOT ask what you can resolve yourself (re-reading the requirement, checking the target "
                + "page/file/data first), and do NOT ask small-talk confirmations; every question must include "
                + "enough context that the user can answer it without reading this conversation.")
        public String askUser(
                @ToolParam(name = "questions", required = true,
                        description = "Questions for the user, 1-5 items. Ask everything you need in this one "
                                + "call instead of asking repeatedly")
                List<String> questions,
                @ToolParam(name = "options", required = true,
                        description = "Candidate answers, same length and order as questions. Each element "
                                + "lists that question's options separated by newlines (max 3, each "
                                + "self-contained so the user understands it without re-reading the question); "
                                + "use an empty string for a question that needs free-text input only")
                List<String> options) {
            if (questions == null || questions.isEmpty()) {
                return "调用失败：questions 不能为空，请把要问的问题整理后重新调用。";
            }
            if (options == null) {
                options = List.of();
            }
            List<String> qs = questions.stream().filter(q -> q != null && !q.isBlank())
                    .map(String::trim).limit(MAX_QUESTIONS).toList();
            if (qs.isEmpty()) {
                return "调用失败：questions 不能为空，请把要问的问题整理后重新调用。";
            }
            List<List<String>> parsed = new ArrayList<>();
            for (int i = 0; i < qs.size(); i++) {
                String raw = i < options.size() && options.get(i) != null ? options.get(i) : "";
                parsed.add(parseOptions(raw));
            }
            List<AskUserService.AskOutcome> outcomes = askUsers.ask(emitter, agent, conversationId.get(), qs, parsed);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < qs.size(); i++) {
                String q = qs.get(i);
                AskUserService.AskOutcome o = outcomes.get(i);
                String source;
                if (o.answered() && o.fromProxy()) {
                    source = "编排者「" + (proxyName != null ? proxyName.get() : "编排者") + "」代答：" + o.answer();
                } else if (o.answered()) {
                    source = "用户回答：" + o.answer();
                } else {
                    source = o.answer() + "。请按你的最佳判断继续，并在回复中说明你的假设。";
                }
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append("问题").append(i + 1).append("「").append(q).append("」→ ").append(source);
                if (note != null) {
                    note.accept("成员「" + agent.getName() + "」提问：" + q + "\n→ " + source);
                }
            }
            return sb.toString();
        }

        /** 选项文本按换行拆分，最多 3 个（前端再附「其他」自由输入） */
        private static List<String> parseOptions(String raw) {
            if (raw == null || raw.isBlank()) {
                return List.of();
            }
            return java.util.Arrays.stream(raw.split("\n"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .limit(MAX_OPTIONS)
                    .toList();
        }
    }
}
