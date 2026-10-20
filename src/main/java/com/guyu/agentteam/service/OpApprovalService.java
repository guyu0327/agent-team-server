package com.guyu.agentteam.service;

import com.guyu.agentteam.common.Ids;
import com.guyu.agentteam.entity.Agent;
import com.guyu.agentteam.entity.AppLog;
import com.guyu.agentteam.entity.OperationGrant;
import com.guyu.agentteam.entity.OperationGrantId;
import com.guyu.agentteam.repository.OperationGrantRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 受控操作（write_file/edit_file/execute）的聊天内审批：
 * 智能体调用受控工具时向前端发 op_request 卡片并原地阻塞等待用户决定；
 * 「允许一次」只放行本次调用，「本会话允许」落库后该会话同类操作不再询问，
 * 同时放行该会话同类型的其他待审批请求（前端同步关闭其余卡片）。
 */
@Service
public class OpApprovalService {

    /** 用户未响应的等待上限，超时按未批准处理 */
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(120);
    /** 卡片详情（写入内容/命令文本）最长传输长度，超出截断 */
    private static final int MAX_DETAIL = 4000;

    private final OperationGrantRepository grants;
    private final ConversationStreamSupport support;
    private final AppLogService appLogs;
    private final BrowserSettingsService browserSettings;

    private final Map<String, PendingRequest> pending = new ConcurrentHashMap<>();

    private static final class PendingRequest {
        final CompletableFuture<String> future = new CompletableFuture<>();
        final String conversationId;
        final String opType;
        final String target;
        final String summary;

        PendingRequest(String conversationId, String opType, String target, String summary) {
            this.conversationId = conversationId;
            this.opType = opType;
            this.target = target;
            this.summary = summary;
        }
    }

    public OpApprovalService(OperationGrantRepository grants, ConversationStreamSupport support,
                             AppLogService appLogs, BrowserSettingsService browserSettings) {
        this.grants = grants;
        this.support = support;
        this.appLogs = appLogs;
        this.browserSettings = browserSettings;
    }

    /** 智能体一次受控工具调用的审批流程：发卡片 → 阻塞等决定 → 是否放行 */
    public boolean approve(SseEmitter emitter, Agent agent, String conversationId,
                           String opType, String target, String detail) {
        return approve(emitter, agent, conversationId, opType, target, detail, false, false, false, false);
    }

    /**
     * 带回合策略的审批：定时任务触发回合是后台自动执行，没人响应审批卡片（等满 120 秒只会白等后拒绝）。
     * backgroundRound=true 时按任务的放行策略直接裁决：已授权（会话授权或任务允许）→ 放行并记
     * 「后台自动放行」日志；任务不允许 → 立即拒绝（不阻塞 120 秒）并记「后台未授权拒绝」日志。
     * 普通聊天回合（backgroundRound=false）行为与旧签名完全一致。
     */
    public boolean approve(SseEmitter emitter, Agent agent, String conversationId,
                           String opType, String target, String detail,
                           boolean backgroundRound, boolean autoWriteAllowed, boolean autoShellAllowed,
                           boolean autoBrowserAllowed) {
        if (conversationId == null || conversationId.isBlank()) {
            return false;
        }
        if (grants.existsById(new OperationGrantId(conversationId, opType))) {
            return true;
        }
        String summary = "「" + agent.getName() + "」请求 " + opType
                + (target == null || target.isBlank() ? "" : " → " + target);
        // 域名白名单是用户的显式预先授权：命中（含子域名）的网页打开不弹卡片、不等后台策略，直接放行
        if (OperationGrant.OP_BROWSER.equals(opType) && browserSettings.isWhitelisted(target)) {
            appLogs.record(AppLog.TYPE_OP_DECISION, conversationId, agent.getId(),
                    "「域名白名单放行」" + summary);
            return true;
        }
        if (backgroundRound) {
            boolean allowed = switch (opType) {
                case OperationGrant.OP_SHELL -> autoShellAllowed;
                case OperationGrant.OP_BROWSER -> autoBrowserAllowed;
                default -> autoWriteAllowed;
            };
            appLogs.record(AppLog.TYPE_OP_DECISION, conversationId, agent.getId(),
                    allowed ? "「后台自动放行」" + summary : "「后台未授权拒绝」" + summary
                            + "（可在任务设置中开启对应操作的自动放行）");
            return allowed;
        }
        String requestId = Ids.next();
        PendingRequest pr = new PendingRequest(conversationId, opType, target, summary);
        pending.put(requestId, pr);
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("requestId", requestId);
            payload.put("conversationId", conversationId);
            payload.put("opType", opType);
            payload.put("agentId", agent.getId());
            payload.put("agentName", agent.getName());
            payload.put("target", target);
            payload.put("detail", truncate(detail));
            support.send(emitter, "op_request", payload);
            appLogs.record(AppLog.TYPE_OP_REQUEST, conversationId, agent.getId(), summary);
            String decision;
            try {
                decision = pr.future.get(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                decision = null;
            }
            return OperationGrant.DECISION_ONCE.equals(decision)
                    || OperationGrant.DECISION_CONVERSATION.equals(decision)
                    || OperationGrant.DECISION_WHITELIST.equals(decision);
        } finally {
            pending.remove(requestId);
        }
    }

    /**
     * 审批卡片回调：once 放行一次；conversation 落库并放行，且放行该会话同类型的其他待审批请求
     * （这些请求早已越过落库检查在原地等待，需逐一唤醒，前端同步关闭其余卡片）；deny 拒绝；
     * whitelist 把目标域名加入全局白名单并放行本次，同会话其他命中该域名的待审批浏览器请求一并放行。
     * 返回 false 表示请求已超时失效。
     */
    public boolean decide(String requestId, String decision) {
        PendingRequest pr = pending.remove(requestId);
        if (pr == null) {
            return false;
        }
        if (OperationGrant.DECISION_CONVERSATION.equals(decision)) {
            grants.save(new OperationGrant(pr.conversationId, pr.opType, System.currentTimeMillis()));
        }
        if (OperationGrant.DECISION_WHITELIST.equals(decision)) {
            String host = BrowserSettingsService.hostOf(pr.target);
            if (host != null) {
                browserSettings.addDomain(host);
            } else {
                // 目标不是可解析的 URL：无从加白，退化为仅放行本次
                decision = OperationGrant.DECISION_ONCE;
            }
        }
        pr.future.complete(decision);
        appLogs.record(AppLog.TYPE_OP_DECISION, pr.conversationId, null,
                "「" + labelOf(decision) + "」" + pr.summary);
        if (OperationGrant.DECISION_CONVERSATION.equals(decision)) {
            for (Map.Entry<String, PendingRequest> e : pending.entrySet()) {
                PendingRequest other = e.getValue();
                if (pr.conversationId.equals(other.conversationId) && pr.opType.equals(other.opType)
                        && pending.remove(e.getKey(), other)) {
                    other.future.complete(decision);
                    appLogs.record(AppLog.TYPE_OP_DECISION, other.conversationId, null,
                            "「" + labelOf(decision) + "」（批量放行）" + other.summary);
                }
            }
        }
        if (OperationGrant.DECISION_WHITELIST.equals(decision)) {
            String host = BrowserSettingsService.hostOf(pr.target);
            for (Map.Entry<String, PendingRequest> e : pending.entrySet()) {
                PendingRequest other = e.getValue();
                String otherHost = other.target == null ? null : BrowserSettingsService.hostOf(other.target);
                if (pr.conversationId.equals(other.conversationId)
                        && OperationGrant.OP_BROWSER.equals(other.opType)
                        && host != null && otherHost != null
                        && (otherHost.equals(host) || otherHost.endsWith("." + host))
                        && pending.remove(e.getKey(), other)) {
                    other.future.complete(decision);
                    appLogs.record(AppLog.TYPE_OP_DECISION, other.conversationId, null,
                            "「" + labelOf(decision) + "」（批量放行）" + other.summary);
                }
            }
        }
        return true;
    }

    /** 终止协作/讨论时唤醒该会话所有待审批请求（按拒绝处理），返回被关闭的 requestId 列表 */
    public List<String> cancelAllForConversation(String conversationId) {
        List<String> cancelled = new ArrayList<>();
        for (Map.Entry<String, PendingRequest> e : pending.entrySet()) {
            PendingRequest pr = e.getValue();
            if (pr.conversationId.equals(conversationId) && pending.remove(e.getKey(), pr)) {
                pr.future.complete(null);
                cancelled.add(e.getKey());
            }
        }
        return cancelled;
    }

    private static String labelOf(String decision) {
        if (OperationGrant.DECISION_ONCE.equals(decision)) return "允许一次";
        if (OperationGrant.DECISION_CONVERSATION.equals(decision)) return "本会话允许";
        if (OperationGrant.DECISION_WHITELIST.equals(decision)) return "加入白名单";
        return "拒绝";
    }

    private String truncate(String detail) {
        if (detail == null || detail.length() <= MAX_DETAIL) {
            return detail;
        }
        return detail.substring(0, MAX_DETAIL) + "\n…（内容过长已截断）";
    }
}
