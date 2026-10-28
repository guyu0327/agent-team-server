package com.guyu.agentteam.service.qq;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.guyu.agentteam.common.ApiException;
import com.guyu.agentteam.common.CurrentUser;
import com.guyu.agentteam.common.Ids;
import com.guyu.agentteam.common.Images;
import com.guyu.agentteam.common.Json;
import com.guyu.agentteam.common.Str;
import com.guyu.agentteam.dto.MessageDto;
import com.guyu.agentteam.entity.Agent;
import com.guyu.agentteam.entity.AppLog;
import com.guyu.agentteam.entity.Conversation;
import com.guyu.agentteam.entity.ConversationFileGrant;
import com.guyu.agentteam.entity.ConversationMember;
import com.guyu.agentteam.entity.Message;
import com.guyu.agentteam.entity.QqBinding;
import com.guyu.agentteam.repository.AgentRepository;
import com.guyu.agentteam.repository.ConversationMemberRepository;
import com.guyu.agentteam.repository.ConversationRepository;
import com.guyu.agentteam.repository.MessageRepository;
import com.guyu.agentteam.repository.QqBindingRepository;
import com.guyu.agentteam.service.AppLogService;
import com.guyu.agentteam.service.ChatStreamService;
import com.guyu.agentteam.service.ConversationService;
import com.guyu.agentteam.service.ConversationStreamSupport;
import com.guyu.agentteam.service.MessageService;
import com.guyu.agentteam.service.SettingsStore;
import com.guyu.agentteam.service.tool.WorkspaceFileTools;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QQ 官方开放平台机器人通道：AppID/Secret 换凭证后出站 WSS 长连接收事件，
 * 按发送者（私聊按用户 openid、群聊按群 openid）绑定到系统会话并复用 ChatStreamService 全管线，
 * 智能体文本段落攒批发回 QQ。被动回复每条入站消息限 5 条/5 分钟，故攒批推送、超出记日志。
 * 权限边界：私聊三档（开放/白名单/停用）；群聊过群白名单后全员可对话，
 * 工具仅机主（扫码绑定人）回合放行——其他人的回合不挂任何工具（含只读文件工具），只允许纯聊天，
 * 键鼠/浏览器操控永远不放行。
 */
@Service
public class QqChannelService {

    static final String SETTINGS_KEY = "qq.channel";
    static final String CREDENTIAL_KEY = "qq.bot";
    static final String SEEN_KEY = "qq.seen";

    private static final ObjectMapper MAPPER = Json.mapper();
    /** 收到的 msg_id 去重缓存上限（WSS 断线由 Resume 补发，这里兜底防重） */
    private static final int SEEN_CAP = 1000;
    /** 被动回复上限：平台规定同一条 msg_id 最多回 5 条 */
    private static final int PASSIVE_REPLY_CAP = 5;
    /** 攒批阈值：待发文本累计超过该字数即发一条，尽量在 5 条额度内送出更多内容 */
    private static final int BATCH_CHARS = 600;
    /** seen 列表保留的最近联系人上限 */
    private static final int SEEN_PEERS_CAP = 50;
    /** 智能体回复中的本地图片引用：![任意alt](路径)，出站时转成富媒体发送 */
    private static final Pattern IMAGE_REF = Pattern.compile("!\\[[^\\]]*]\\(([^)]+)\\)");

    public static final String POLICY_OFF = "off";
    public static final String POLICY_OPEN = "open";
    public static final String POLICY_WHITELIST = "whitelist";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChannelSettings(boolean enabled, String agentId, boolean autoWrite, boolean autoShell,
                                  int maxReplyChars, int roundTimeoutMinutes,
                                  String c2cPolicy, List<String> c2cWhitelist,
                                  String groupPolicy, List<String> groupWhitelist) {
        public static ChannelSettings defaults() {
            return new ChannelSettings(true, "", false, false, 2000, 10,
                    POLICY_OPEN, List.of(), POLICY_OPEN, List.of());
        }
    }

    /** ownerOpenid 为扫码绑定人（机主），仅机主的回合放行写改/命令工具；手动绑定且未扫码时为 null=无人可操作 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QqCredential(String appId, String appSecret, String savedAt, String ownerOpenid) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeenList(List<SeenPeer> peers) {
    }

    /** 最近收到过消息的对端（含被权限挡掉的）：私聊按用户、群聊按群成员记，供弹层一键加白名单；name 为平台下发的用户名（可能为 null） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeenPeer(String kind, String openid, String groupOpenid, String name, String preview, long lastSeenAt) {
    }

    /** 事件类型：kind=c2c 时 openid 为用户 openid；kind=group 时 openid 为群 openid、memberOpenid 为群成员 openid；
     *  senderName 为 author.username（可能为 null）、senderRole 为 author.member_role（owner/admin，普通成员为空） */
    public record Inbound(String msgId, String kind, String openid, String memberOpenid,
                          String senderName, String senderRole,
                          String text, List<Attachment> attachments) {
    }

    public record Attachment(String contentType, String url) {
    }

    /** 回复目标：kind + 对端 openid（私聊=用户，群聊=群）+ 原消息 msg_id */
    private record ReplyTarget(String kind, String openid, String msgId) {
    }

    private final QqOpenApiClient api;
    private final SettingsStore settings;
    private final QqBindingRepository bindings;
    private final ConversationRepository conversations;
    private final ConversationMemberRepository members;
    private final AgentRepository agents;
    private final MessageRepository messages;
    private final MessageService messageService;
    private final AppLogService appLogs;
    private final WorkspaceFileTools fileTools;
    private final ObjectProvider<ChatStreamService> chatStream;
    private final ObjectProvider<ConversationService> conversationService;
    private final ConversationStreamSupport support;
    /** 事件线程无事务上下文，建会话/绑定等派生操作需显式包事务 */
    private final TransactionTemplate tx;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private volatile QqCredential credential;
    private volatile QqWsClient wsClient;
    private Thread supervisorThread;

    private final Set<String> seenIds = new LinkedHashSet<>();
    /** 同一条 msg_id 的被动回复序号，平台要求按次序递增 */
    private final Map<String, Integer> replySeq = new ConcurrentHashMap<>();
    /** 每次进程只记一条 author 原始 JSON 样例（核实平台是否下发 username） */
    private final AtomicBoolean authorSampleLogged = new AtomicBoolean(false);

    public QqChannelService(QqOpenApiClient api, SettingsStore settings,
                            QqBindingRepository bindings, ConversationRepository conversations,
                            ConversationMemberRepository members, AgentRepository agents,
                            MessageRepository messages, MessageService messageService,
                            AppLogService appLogs, WorkspaceFileTools fileTools,
                            ObjectProvider<ChatStreamService> chatStream,
                            ObjectProvider<ConversationService> conversationService,
                            ConversationStreamSupport support, TransactionTemplate tx) {
        this.api = api;
        this.settings = settings;
        this.bindings = bindings;
        this.conversations = conversations;
        this.members = members;
        this.agents = agents;
        this.messages = messages;
        this.messageService = messageService;
        this.appLogs = appLogs;
        this.fileTools = fileTools;
        this.chatStream = chatStream;
        this.conversationService = conversationService;
        this.support = support;
        this.tx = tx;
        this.credential = loadCredential();
    }

    // ---------- 设置与凭证 ----------

    public ChannelSettings loadSettings() {
        String raw = settings.read(SETTINGS_KEY);
        if (Str.isBlank(raw)) {
            return ChannelSettings.defaults();
        }
        try {
            ChannelSettings s = MAPPER.readValue(raw, ChannelSettings.class);
            return new ChannelSettings(s.enabled(), s.agentId() == null ? "" : s.agentId(),
                    s.autoWrite(), s.autoShell(),
                    clamp(s.maxReplyChars(), 200, 20000, 2000),
                    clamp(s.roundTimeoutMinutes(), 1, 60, 10),
                    normalizePolicy(s.c2cPolicy(), POLICY_OPEN), normalizeList(s.c2cWhitelist()),
                    normalizePolicy(s.groupPolicy(), POLICY_OPEN), normalizeList(s.groupWhitelist()));
        } catch (Exception e) {
            return ChannelSettings.defaults();
        }
    }

    public ChannelSettings saveSettings(ChannelSettings req) {
        ChannelSettings merged = new ChannelSettings(
                req.enabled(),
                req.agentId() == null ? "" : req.agentId().trim(),
                req.autoWrite(), req.autoShell(),
                clamp(req.maxReplyChars(), 200, 20000, 2000),
                clamp(req.roundTimeoutMinutes(), 1, 60, 10),
                normalizePolicy(req.c2cPolicy(), POLICY_OPEN), normalizeList(req.c2cWhitelist()),
                normalizePolicy(req.groupPolicy(), POLICY_OPEN), normalizeList(req.groupWhitelist()));
        settings.write(SETTINGS_KEY, MAPPER.writeValueAsString(merged));
        return loadSettings();
    }

    private static int clamp(int v, int min, int max, int dft) {
        return v == 0 ? dft : Math.max(min, Math.min(max, v));
    }

    private static String normalizePolicy(String p, String dft) {
        if (POLICY_OFF.equals(p) || POLICY_OPEN.equals(p) || POLICY_WHITELIST.equals(p)) {
            return p;
        }
        return dft;
    }

    private static List<String> normalizeList(List<String> list) {
        if (list == null) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String item : list) {
            if (item != null && !item.isBlank()) {
                out.add(item.trim());
            }
        }
        return List.copyOf(out);
    }

    private QqCredential loadCredential() {
        String raw = settings.read(CREDENTIAL_KEY);
        if (Str.isBlank(raw)) {
            return null;
        }
        try {
            QqCredential c = MAPPER.readValue(raw, QqCredential.class);
            return Str.isBlank(c.appId()) || Str.isBlank(c.appSecret()) ? null : c;
        } catch (Exception e) {
            return null;
        }
    }

    /** 保存凭证：先实际换一次 token 验证，失败抛业务异常不落库；成功后立即重启连接 */
    public void saveCredential(String appId, String appSecret, String ownerOpenid) {
        String id = appId == null ? "" : appId.trim();
        String secret = appSecret == null ? "" : appSecret.trim();
        if (Str.isBlank(id) || Str.isBlank(secret)) {
            throw ApiException.badRequest("AppID 和 AppSecret 不能为空");
        }
        api.accessToken(id, secret);
        // 机主以扫码绑定人为准；手动重存同机器人时保留原机主，换绑机器人则清空
        String owner = !Str.isBlank(ownerOpenid) ? ownerOpenid.trim()
                : credential != null && id.equals(credential.appId()) ? credential.ownerOpenid() : null;
        QqCredential cred = new QqCredential(id, secret,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")), owner);
        credential = cred;
        settings.write(CREDENTIAL_KEY, MAPPER.writeValueAsString(cred));
        stopWs();
        ensureSupervisor();
        chanLog("QQ 凭证已保存并连接（AppID " + id + (owner != null ? "，机主 " + shortId(owner) : "，未记录机主（需扫码绑定），所有人暂无法操作本机") + "）");
    }

    /** 断开连接：清除凭证并停连（绑定关系保留，重新配置后消息继续进原会话） */
    public void disconnect() {
        QqCredential c = credential;
        if (c != null) {
            credential = null;
            settings.write(CREDENTIAL_KEY, "");
            stopWs();
            chanLog("QQ 通道已断开（AppID " + c.appId() + "）");
        }
    }

    // ---------- 状态 ----------

    public record Status(boolean connected, boolean enabled, QqCredential credential, ChannelSettings settings,
                         List<SeenPeer> seen) {
    }

    public Status status() {
        QqWsClient c = wsClient;
        return new Status(c != null && c.isConnected(), loadSettings().enabled(), credential, loadSettings(),
                loadSeenPeers());
    }

    // ---------- WSS 生命周期 ----------

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        ensureSupervisor();
    }

    private synchronized void ensureSupervisor() {
        if (supervisorThread != null && supervisorThread.isAlive()) {
            return;
        }
        Thread t = new Thread(this::supervisorLoop, "qq-channel");
        t.setDaemon(true);
        t.start();
        supervisorThread = t;
    }

    @SuppressWarnings("BusyWait")
    private void supervisorLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                QqCredential cred = credential;
                ChannelSettings s = loadSettings();
                if (cred == null || !s.enabled()) {
                    stopWs();
                    Thread.sleep(3000);
                    continue;
                }
                QqWsClient c = wsClient;
                if (c == null || !c.isRunning()) {
                    stopWs();
                    c = new QqWsClient(() -> api.accessToken(cred.appId(), cred.appSecret()),
                            this::onDispatch, this::onWsState);
                    wsClient = c;
                    c.start();
                }
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                chanLog("QQ 通道守护异常：" + message(e));
                sleepQuiet(3000);
            }
        }
    }

    private void stopWs() {
        QqWsClient c = wsClient;
        wsClient = null;
        if (c != null) {
            c.stop();
        }
    }

    private void onWsState(boolean connected, String error) {
        if (connected) {
            QqCredential cred = credential;
            chanLog("QQ 通道已连接（AppID " + (cred == null ? "?" : cred.appId()) + "）");
        } else {
            logErr(null, "QQ 连接断开：" + (error == null ? "未知原因" : error));
        }
    }

    // ---------- 事件处理 ----------

    private void onDispatch(String type, JsonNode d) {
        try {
            switch (type) {
                case "C2C_MESSAGE_CREATE" -> {
                    logAuthorSample(d.path("author"));
                    handle(c2cInbound(d), loadSettings());
                }
                case "GROUP_AT_MESSAGE_CREATE" -> {
                    logAuthorSample(d.path("author"));
                    handle(groupInbound(d), loadSettings());
                }
                default -> {
                    // READY/RESUMED 由 WsClient 处理，其余事件忽略
                }
            }
        } catch (Exception e) {
            logErr(null, "处理 QQ 消息失败：" + message(e));
        }
    }

    /** 每次进程首条消息把 author 原始 JSON 记进通道日志：核实平台是否随事件下发 username */
    private void logAuthorSample(JsonNode author) {
        if (author != null && !author.isMissingNode() && authorSampleLogged.compareAndSet(false, true)) {
            chanLog("QQ 事件 author 样例：" + truncate(author.toString(), 400));
        }
    }

    /** 平台随事件下发的用户名（author.username，旧版本无此字段则为 null） */
    private static String senderName(JsonNode author) {
        String name = firstNonBlank(author.path("username").asText(""), author.path("nickname").asText(""));
        return Str.isBlank(name) ? null : name;
    }

    /** 群角色（author.member_role：owner/admin），普通成员或字段缺失为 null */
    private static String senderRole(JsonNode author) {
        String role = author.path("member_role").asText("");
        return Str.isBlank(role) ? null : role;
    }

    private Inbound c2cInbound(JsonNode d) {
        JsonNode author = d.path("author");
        String openid = firstNonBlank(author.path("user_openid").asText(""), author.path("id").asText(""));
        return new Inbound(d.path("id").asText(""), "c2c", openid, null,
                senderName(author), senderRole(author),
                d.path("content").asText("").trim(), attachments(d));
    }

    private Inbound groupInbound(JsonNode d) {
        JsonNode author = d.path("author");
        String memberOpenid = firstNonBlank(author.path("member_openid").asText(""), author.path("id").asText(""));
        return new Inbound(d.path("id").asText(""), "group", d.path("group_openid").asText(""), memberOpenid,
                senderName(author), senderRole(author),
                d.path("content").asText("").trim(), attachments(d));
    }

    private static List<Attachment> attachments(JsonNode d) {
        List<Attachment> out = new ArrayList<>();
        for (JsonNode a : d.path("attachments")) {
            String url = a.path("url").asText("");
            if (!Str.isBlank(url)) {
                out.add(new Attachment(a.path("content_type").asText(""), url));
            }
        }
        return List.copyOf(out);
    }

    private static String firstNonBlank(String a, String b) {
        return Str.isBlank(a) ? b : a;
    }

    private void handle(Inbound m, ChannelSettings s) {
        if (Str.isBlank(m.msgId()) || !markSeen(m.msgId())) {
            return;
        }
        recordSeen(m);
        if (!accessAllowed(m, s)) {
            return;
        }
        ReplyTarget reply = new ReplyTarget(m.kind(), m.openid(), m.msgId());
        if (Str.isBlank(m.text()) && m.attachments().isEmpty()) {
            chanLog("收到 QQ 空消息（无文本无附件），已忽略");
            return;
        }
        Conversation conv = resolveConversation(m);
        Agent agent = resolveAgent();
        List<ConversationFileGrant> grants = List.of();
        if (!m.attachments().isEmpty()) {
            try {
                grants = saveAttachments(conv, m);
                logQq(conv.getId(), "已接收 QQ 附件 " + grants.size() + " 个");
            } catch (Exception e) {
                logQq(conv.getId(), "QQ 附件接收失败：" + message(e));
                if (Str.isBlank(m.text())) {
                    sendReply(reply, "（附件接收失败：" + truncate(message(e), 120) + "，请重试或改用文字发送）");
                    return;
                }
            }
        }
        // 群消息记群成员 openid（私聊记用户 openid）与平台下发的昵称/群角色：气泡显示「昵称（openid）」+ 角色徽标
        String senderOpenid = m.memberOpenid() != null && !m.memberOpenid().isBlank() ? m.memberOpenid() : m.openid();
        Message userMsg = messageService.createUserMessage(conv, m.text(), grants, senderOpenid, m.senderName(), m.senderRole());
        ChatStreamService stream = chatStream.getIfAvailable();
        if (stream == null) {
            sendReply(reply, "（会话服务暂不可用，请稍后再试）");
            return;
        }
        // 工具只对机主放行：其余人（含私聊好友与全部群成员）照常对话，但其回合禁用一切工具
        //（含只读文件工具——能读就能翻遍授权目录），只允许纯聊天
        String sender = "group".equals(m.kind()) ? m.memberOpenid() : m.openid();
        boolean operator = credential != null && !Str.isBlank(credential.ownerOpenid())
                && credential.ownerOpenid().equals(sender);
        if (!operator) {
            logQq(conv.getId(), "发送者 " + shortId(sender) + " 非机主，本回合禁用一切工具，仅纯聊天");
        }
        long deadline = System.currentTimeMillis() + s.roundTimeoutMinutes() * 60_000L;
        // 与定时任务相同的重入保护：上一轮未结束先等空闲，避免两轮流交错
        while (stream.isRunning(conv.getId()) && System.currentTimeMillis() < deadline) {
            sleepQuiet(500);
        }
        // 后台回合：无人盯审批，机主回合按通道配置自动放行或立即拒绝（默认都不放行）；
        // 浏览器与键鼠操控始终不放行（autoBrowser=false），QQ 侧不提供电脑操控入口
        stream.stream(new ConversationStreamSupport.Broadcast(conv.getId()), conv, userMsg,
                new ConversationStreamSupport.TaskTag(null, null, s.autoWrite() && operator, s.autoShell() && operator,
                        false, false, !operator));
        // 等回合真正开跑（executor 异步提交，isRunning 置位在任务线程里；超时兜底防卡死）
        long startWait = System.currentTimeMillis();
        while (!stream.isRunning(conv.getId()) && System.currentTimeMillis() - startWait < 10_000) {
            sleepQuiet(100);
        }
        Pusher pusher = new Pusher(reply);
        // 出段即推：段落文本落库（=该段完整）就发给 QQ，不等回合结束（多智能体回合可拖几十秒）；
        // 平台被动回复额度每条入站消息 5 条，超额部分记日志留在应用内
        while (stream.isRunning(conv.getId()) && System.currentTimeMillis() < deadline) {
            pusher.drain(conv, userMsg);
            pusher.finish();
            sleepQuiet(500);
        }
        pusher.drain(conv, userMsg);
        pusher.finish();
        if (pusher.sentCount() == 0) {
            sendReply(reply, "（这轮回复没能按时完成，请稍后再问我一次）");
        }
    }

    // ---------- 权限边界 ----------

    private boolean accessAllowed(Inbound m, ChannelSettings s) {
        if ("c2c".equals(m.kind())) {
            return switch (s.c2cPolicy()) {
                case POLICY_OFF -> {
                    chanLog("私聊已停用，忽略来自 " + shortId(m.openid()) + " 的消息");
                    yield false;
                }
                case POLICY_WHITELIST -> {
                    if (s.c2cWhitelist().contains(m.openid())) {
                        yield true;
                    }
                    chanLog("私聊发送者 " + shortId(m.openid()) + " 不在白名单，忽略其消息");
                    yield false;
                }
                default -> true;
            };
        }
        // 群聊：白名单只决定哪些群会被响应；群内谁都能对话，操作权限在 handle 里只对机主放行
        if (POLICY_OFF.equals(s.groupPolicy())) {
            chanLog("群聊已停用，忽略群 " + shortId(m.openid()) + " 的消息");
            return false;
        }
        if (POLICY_WHITELIST.equals(s.groupPolicy()) && !s.groupWhitelist().contains(m.openid())) {
            chanLog("群 " + shortId(m.openid()) + " 不在白名单，忽略其消息");
            return false;
        }
        return true;
    }

    // ---------- seen 列表 ----------

    private void recordSeen(Inbound m) {
        if (Str.isBlank(m.msgId()) || (Str.isBlank(m.text()) && m.attachments().isEmpty())) {
            return;
        }
        try {
            String who = m.memberOpenid() != null ? m.memberOpenid() : m.openid();
            String groupOpenid = "group".equals(m.kind()) ? m.openid() : null;
            String preview = truncate(Str.isBlank(m.text()) ? "（图片等附件）" : m.text(), 40);
            List<SeenPeer> peers = new ArrayList<>(loadSeen().peers());
            String key = m.kind() + ":" + who + ":" + (groupOpenid == null ? "" : groupOpenid);
            peers.removeIf(p -> (p.kind() + ":" + p.openid() + ":"
                    + (p.groupOpenid() == null ? "" : p.groupOpenid())).equals(key));
            peers.add(0, new SeenPeer(m.kind(), who, groupOpenid, m.senderName(), preview, System.currentTimeMillis()));
            while (peers.size() > SEEN_PEERS_CAP) {
                peers.remove(peers.size() - 1);
            }
            settings.write(SEEN_KEY, MAPPER.writeValueAsString(new SeenList(peers)));
        } catch (Exception ignored) {
            // seen 记录失败不影响消息处理
        }
    }

    private SeenList loadSeen() {
        String raw = settings.read(SEEN_KEY);
        if (Str.isBlank(raw)) {
            return new SeenList(List.of());
        }
        try {
            SeenList list = MAPPER.readValue(raw, SeenList.class);
            return list == null || list.peers() == null ? new SeenList(List.of()) : list;
        } catch (Exception e) {
            return new SeenList(List.of());
        }
    }

    private List<SeenPeer> loadSeenPeers() {
        return loadSeen().peers();
    }

    // ---------- 会话路由 ----------

    private Conversation resolveConversation(Inbound m) {
        String key = bindingKey(m);
        // 对端短标识：私聊取用户 openid，群聊取群 openid。不能取群内成员 openid——
        // QQ 平台成员 openid 与该用户私聊 openid 相同，会让私聊/群两个会话撞名
        String peerShort = shortId(m.openid());
        Conversation c = tx.execute(status -> {
            QqBinding b = bindings.findById(key).orElse(null);
            if (b != null) {
                Conversation existing = conversations.findById(b.getConversationId()).orElse(null);
                if (existing != null && existing.getArchivedAt() == null) {
                    // 自愈：修正早期版本的 peer（群误存成员 openid）并回填类型
                    if (!peerShort.equals(existing.getQqPeer()) || !m.kind().equals(existing.getQqKind())) {
                        existing.setQqPeer(peerShort);
                        existing.setQqKind(m.kind());
                        conversations.save(existing);
                    }
                    syncMember(existing);
                    return existing;
                }
                bindings.delete(b);
            }
            Conversation nc = conversationService.getObject().newConversation(
                    CurrentUser.ID, "single", ConversationService.CATEGORY_CHAT,
                    "group".equals(m.kind()) ? "QQ群" : "QQ", "qq");
            nc.setQqPeer(peerShort);
            nc.setQqKind(m.kind());
            conversations.save(nc);
            members.save(new ConversationMember(nc.getId(), resolveAgent().getId(), System.currentTimeMillis()));
            QqBinding nb = new QqBinding();
            nb.setSenderKey(key);
            nb.setConversationId(nc.getId());
            nb.setCreatedAt(System.currentTimeMillis());
            bindings.save(nb);
            logQq(nc.getId(), ("group".equals(m.kind()) ? "QQ 群 " : "QQ 用户 ") + peerShort + " 已绑定新会话");
            return nc;
        });
        return c;
    }

    private static String bindingKey(Inbound m) {
        return "group".equals(m.kind()) ? "g:" + m.openid() : "u:" + m.openid();
    }

    /**
     * 重置 QQ 会话：旧会话归档进历史会话（不可恢复聊天），新建会话并迁移绑定，
     * QQ 消息无缝流入新会话——列表里会话不消失，旧聊天记录进历史留档。
     */
    public Conversation resetConversation(String conversationId) {
        Conversation old = conversations.findById(conversationId)
                .orElseThrow(() -> ApiException.notFound("会话不存在"));
        if (!"qq".equals(old.getChannel())) {
            throw ApiException.badRequest("仅 QQ 会话支持重置");
        }
        return tx.execute(status -> {
            conversationService.getObject().archive(conversationId);
            Conversation fresh = conversationService.getObject().newConversation(
                    CurrentUser.ID, "single", ConversationService.CATEGORY_CHAT,
                    "group".equals(old.getQqKind()) ? "QQ群" : "QQ", "qq");
            fresh.setQqKind(old.getQqKind());
            members.save(new ConversationMember(fresh.getId(), resolveAgent().getId(), System.currentTimeMillis()));
            for (QqBinding b : bindings.findByConversationId(conversationId)) {
                b.setConversationId(fresh.getId());
                bindings.save(b);
                if (Str.isBlank(fresh.getQqPeer()) && b.getSenderKey() != null
                        && b.getSenderKey().contains(":")) {
                    fresh.setQqPeer(shortId(b.getSenderKey().split(":", 2)[1]));
                    conversations.save(fresh);
                }
            }
            logQq(fresh.getId(), "QQ 会话已重置：旧会话已归档到历史会话");
            return fresh;
        });
    }

    /** 处理智能体变更即时生效：单聊成员与配置不一致时替换成员行，并落一条系统切换标注 */
    private void syncMember(Conversation c) {
        Agent agent = resolveAgent();
        List<ConversationMember> ms = members.findByConversationIdOrderByCreatedAtAsc(c.getId());
        if (ms.size() == 1 && ms.get(0).getAgentId().equals(agent.getId())) {
            return;
        }
        String fromName = agentName(ms.isEmpty() ? null : ms.get(0).getAgentId());
        members.deleteByConversationId(c.getId());
        members.save(new ConversationMember(c.getId(), agent.getId(), System.currentTimeMillis()));
        Message note = new Message();
        note.setId(Ids.next());
        note.setConversationId(c.getId());
        note.setSenderType("system");
        // messages.sender_id 列 NOT NULL（V1 建表约束），系统标注无发送者，用固定占位
        note.setSenderId("system");
        note.setContent("处理智能体已由「" + fromName + "」切换为「" + agent.getName() + "」");
        // system 类型不进模型历史（historyMsgs 只取 text），前端渲染为分隔条
        note.setType("system");
        note.setCreatedAt(System.currentTimeMillis());
        messages.save(note);
        support.broadcastToWatchers(c.getId(), "system_note", MessageDto.from(note));
        logQq(c.getId(), "处理智能体切换：" + fromName + " → " + agent.getName());
    }

    private String agentName(String agentId) {
        if (agentId == null) {
            return "（未知）";
        }
        return agents.findById(agentId).map(Agent::getName).orElse("（未知）");
    }

    private Agent resolveAgent() {
        ChannelSettings s = loadSettings();
        if (!Str.isBlank(s.agentId())) {
            Agent a = agents.findById(s.agentId()).orElse(null);
            if (a != null) {
                return a;
            }
        }
        List<Agent> all = agents.findAll();
        return all.stream().filter(a -> Boolean.TRUE.equals(a.getIsOrchestrator())).findFirst()
                .orElseGet(() -> all.stream().findFirst()
                        .orElseThrow(() -> ApiException.badRequest("请先创建至少一个智能体")));
    }

    // ---------- 附件 ----------

    /** 附件（v1 仅图片）直链下载落盘到主工作区「QQ接收」子目录（QQ 不加密，无解密步骤） */
    private List<ConversationFileGrant> saveAttachments(Conversation conv, Inbound m) throws Exception {
        List<ConversationFileGrant> grants = new ArrayList<>();
        Path dir = Paths.get(fileTools.primaryRoot(), "QQ接收");
        Files.createDirectories(dir);
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS").format(LocalDateTime.now());
        int i = 0;
        for (Attachment att : m.attachments()) {
            if (!"image".equals(att.contentType())) {
                continue;
            }
            byte[] data = download(att.url());
            String name = "QQ图片_" + stamp + (i++ > 0 ? "_" + i : "") + extOf(data);
            Path target = dir.resolve(stamp + "_" + name);
            Files.write(target, data);
            grants.add(new ConversationFileGrant(conv.getId(), target.toString(),
                    ConversationFileGrant.TYPE_IMAGE, name, System.currentTimeMillis()));
        }
        return grants;
    }

    private byte[] download(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<byte[]> resp = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            throw new IOException("下载返回 " + resp.statusCode());
        }
        return resp.body();
    }

    /** 按魔数猜扩展名（QQ 附件常不带文件名；扩展名供前端按 MIME 渲染） */
    private static String extOf(byte[] d) {
        if (d.length >= 3 && (d[0] & 0xff) == 0xFF && (d[1] & 0xff) == 0xD8) return ".jpg";
        if (d.length >= 4 && (d[0] & 0xff) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return ".png";
        if (d.length >= 3 && d[0] == 'G' && d[1] == 'I' && d[2] == 'F') return ".gif";
        if (d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') return ".webp";
        if (d.length >= 2 && d[0] == 'B' && d[1] == 'M') return ".bmp";
        return "";
    }

    // ---------- 推送 ----------

    /**
     * 段落攒批推送器：智能体文本段落落库后进缓冲，累计超过 BATCH_CHARS 或回合结束时发一条；
     * 平台被动回复每条 msg_id 限 5 条，超出后剩余内容只留在应用内并记日志
     */
    private class Pusher {
        private final ReplyTarget reply;
        private final Set<String> pushed = new LinkedHashSet<>();
        private final StringBuilder batch = new StringBuilder();
        private int sent;

        Pusher(ReplyTarget reply) {
            this.reply = reply;
        }

        void drain(Conversation conv, Message userMsg) {
            long from = userMsg.getCreatedAt();
            List<Path> allowedRoots = fileTools.allowedRootsFor(conv.getId());
            for (Message msg : messages.findByConversationIdOrderByCreatedAtAsc(conv.getId())) {
                if (msg.getCreatedAt() < from || !"agent".equals(msg.getSenderType())
                        || !"text".equals(msg.getType()) || Str.isBlank(msg.getContent())
                        || !pushed.add(msg.getId())) {
                    continue;
                }
                // 图片引用先于攒批处理：可发的先冲掉已攒文本再按富媒体发送（保证图紧跟其上下文），
                // 引用统一从文本剔除——QQ 侧无法渲染本地路径，剩余文字照旧攒批
                String content = sendImages(conv, msg.getContent(), allowedRoots);
                if (Str.isBlank(content)) {
                    continue;
                }
                if (batch.length() > 0) {
                    batch.append("\n\n");
                }
                batch.append(content);
                if (batch.length() >= BATCH_CHARS) {
                    flush();
                }
            }
        }

        void finish() {
            flush();
        }

        int sentCount() {
            return sent;
        }

        private void flush() {
            if (batch.isEmpty()) {
                return;
            }
            String content = batch.toString();
            batch.setLength(0);
            if (sent >= PASSIVE_REPLY_CAP) {
                chanLog("QQ 被动回复额度已用完（5 条），剩余回复内容请在应用内查看");
                return;
            }
            sent++;
            sendReply(reply, content);
        }

        /** 抽取文本里的本地图片引用并逐张发送，返回剔除引用后的文本；非本地路径引用（如 http 链接）原样保留 */
        private String sendImages(Conversation conv, String content, List<Path> allowedRoots) {
            if (!content.contains("](")) {
                return content;
            }
            Matcher m = IMAGE_REF.matcher(content);
            StringBuffer rest = new StringBuffer();
            while (m.find()) {
                Path img = resolveLocalPath(m.group(1));
                if (img == null) {
                    m.appendReplacement(rest, Matcher.quoteReplacement(m.group(0)));
                    continue;
                }
                m.appendReplacement(rest, "");
                if (!Files.isRegularFile(img) || !Images.isImage(img.getFileName().toString())
                        || !withinRoots(img, allowedRoots)) {
                    chanLog("QQ 跳过图片发送（文件不存在、非图片或不在允许目录内）：" + img);
                    continue;
                }
                // 先冲掉已攒文本，保证 QQ 侧先收到图片前的上下文文字
                flush();
                sendImage(img);
            }
            m.appendTail(rest);
            // 引用剔除后常残留成片空行，压掉避免 QQ 气泡出现空白段
            return rest.toString().replaceAll("\\n{3,}", "\n\n").trim();
        }

        /** 单图发送：上传换 file_info 后按 msg_type 7 发出，计入被动回复额度（上传成功才扣） */
        private void sendImage(Path img) {
            if (sent >= PASSIVE_REPLY_CAP) {
                chanLog("QQ 被动回复额度已用完（5 条），图片未发送：" + img.getFileName());
                return;
            }
            try {
                QqCredential cred = credential;
                if (cred == null) {
                    return;
                }
                String token = api.accessToken(cred.appId(), cred.appSecret());
                String fileInfo = api.uploadImage(token, reply.kind(), reply.openid(), img);
                int seq = replySeq.merge(reply.msgId(), 1, Integer::sum);
                if ("group".equals(reply.kind())) {
                    api.sendGroupImage(token, reply.openid(), fileInfo, reply.msgId(), seq);
                } else {
                    api.sendC2cImage(token, reply.openid(), fileInfo, reply.msgId(), seq);
                }
                sent++;
            } catch (Exception e) {
                chanLog("QQ 图片发送失败：" + img.getFileName() + "（" + message(e) + "）");
            }
        }
    }

    /** 回复统一走这里：msg_seq 按消息维度递增（平台要求同一条消息的回复序号递增，含未授权提示） */
    private void sendReply(ReplyTarget reply, String content) {
        try {
            QqCredential cred = credential;
            if (cred == null) {
                return;
            }
            int seq = replySeq.merge(reply.msgId(), 1, Integer::sum);
            String token = api.accessToken(cred.appId(), cred.appSecret());
            if ("group".equals(reply.kind())) {
                api.sendGroupText(token, reply.openid(), content, reply.msgId(), seq);
            } else {
                api.sendC2cText(token, reply.openid(), content, reply.msgId(), seq);
            }
        } catch (Exception e) {
            logErr(null, "QQ 推送回复失败：" + message(e));
        }
    }

    /** Markdown 引用转本地绝对路径（容错 file:/// 前缀与正反斜杠）；非绝对路径返回 null */
    private static Path resolveLocalPath(String ref) {
        String s = ref == null ? "" : ref.trim();
        if (s.startsWith("file://")) {
            try {
                s = Paths.get(URI.create(s)).toString();
            } catch (Exception e) {
                s = s.substring("file://".length());
            }
        }
        try {
            Path p = Paths.get(s).normalize();
            return p.isAbsolute() ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 只发授权目录覆盖的图片，防止模型引用任意磁盘文件经 QQ 外泄 */
    private static boolean withinRoots(Path img, List<Path> roots) {
        for (Path root : roots) {
            if (img.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    // ---------- 杂项 ----------

    private boolean markSeen(String id) {
        synchronized (seenIds) {
            if (!seenIds.add(id)) {
                return false;
            }
            while (seenIds.size() > SEEN_CAP) {
                seenIds.remove(seenIds.iterator().next());
            }
            return true;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }

    /** openid 尾 8 位做会话列表短标识 */
    private static String shortId(String openid) {
        String s = openid == null ? "" : openid.trim();
        return s.length() <= 8 ? s : s.substring(s.length() - 8);
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private void chanLog(String content) {
        appLogs.record(AppLog.TYPE_QQ, null, null, content);
    }

    private void logQq(String conversationId, String content) {
        appLogs.record(AppLog.TYPE_QQ, conversationId, null, content);
    }

    private void logErr(String conversationId, String content) {
        appLogs.record(AppLog.TYPE_ERROR, conversationId, null, content);
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    void shutdown() {
        stopWs();
        Thread t = supervisorThread;
        if (t != null) {
            t.interrupt();
        }
    }
}
