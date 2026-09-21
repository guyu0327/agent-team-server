package com.guyu.agentteam.service.wechat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.guyu.agentteam.common.ApiException;
import com.guyu.agentteam.common.CurrentUser;
import com.guyu.agentteam.common.Json;
import com.guyu.agentteam.common.Str;
import com.guyu.agentteam.entity.Agent;
import com.guyu.agentteam.entity.AppLog;
import com.guyu.agentteam.entity.Conversation;
import com.guyu.agentteam.entity.ConversationFileGrant;
import com.guyu.agentteam.entity.ConversationMember;
import com.guyu.agentteam.entity.Message;
import com.guyu.agentteam.entity.WechatBinding;
import com.guyu.agentteam.repository.AgentRepository;
import com.guyu.agentteam.repository.ConversationMemberRepository;
import com.guyu.agentteam.repository.ConversationRepository;
import com.guyu.agentteam.repository.MessageRepository;
import com.guyu.agentteam.repository.WechatBindingRepository;
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
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 微信 iLink Bot 通道：扫码登录拿 bot_token 后台长轮询收消息，
 * 按发送者绑定到系统单聊会话并复用 ChatStreamService 全管线跑一轮（后台自动放行策略，
 * 默认写改/终端都不放行），聚合该轮智能体回复经 sendmessage 推回微信。
 * bot_token 与绑定关系持久化；-14 会话超时后需重新扫码。
 */
@Service
public class WechatChannelService {

    static final String SETTINGS_KEY = "wechat.channel";
    static final String BOT_KEY = "wechat.bot";

    private static final ObjectMapper MAPPER = Json.mapper();
    /** 收到的 message_id 去重缓存上限（正常由 get_updates_buf 断点续传，这里兜底防重发） */
    private static final int SEEN_CAP = 1000;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChannelSettings(boolean enabled, String agentId, boolean autoWrite, boolean autoShell,
                                  int maxReplyChars, int roundTimeoutMinutes) {
        public static ChannelSettings defaults() {
            return new ChannelSettings(true, "", false, false, 2000, 10);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BotCredential(String token, String botId, String baseUrl, String ownerUserId, String savedAt) {
    }

    /** 供前端轮询的登录状态：idle 无进行中登录；qr_ready 待扫码；scaned 已扫；need_verifycode 等配对码；confirmed 成功；failed 失败 */
    public record LoginState(String status, String qrSvg, String error, String botId) {
    }

    private final WeixinApiClient api;
    private final SettingsStore settings;
    private final WechatBindingRepository bindings;
    private final ConversationRepository conversations;
    private final ConversationMemberRepository members;
    private final AgentRepository agents;
    private final MessageRepository messages;
    private final MessageService messageService;
    private final AppLogService appLogs;
    private final WechatMediaDownloader downloader;
    private final WorkspaceFileTools fileTools;
    private final ObjectProvider<ChatStreamService> chatStream;
    private final ObjectProvider<ConversationService> conversationService;
    /** 轮询线程无事务上下文，成员替换等派生删除操作需显式包事务 */
    private final TransactionTemplate tx;

    private volatile BotCredential credential;
    private volatile String updatesBuf = "";

    // ---------- 登录状态（内存） ----------
    private final Object loginLock = new Object();
    private volatile LoginState login = new LoginState("idle", null, null, null);
    private volatile String pendingVerifyCode;
    private volatile boolean loginActive;
    private Thread loginThread;

    // ---------- 收消息轮询 ----------
    private Thread pollThread;
    private final Set<String> seenIds = new LinkedHashSet<>();

    public WechatChannelService(WeixinApiClient api, SettingsStore settings,
                                WechatBindingRepository bindings, ConversationRepository conversations,
                                ConversationMemberRepository members, AgentRepository agents,
                                MessageRepository messages, MessageService messageService,
                                AppLogService appLogs, WechatMediaDownloader downloader,
                                WorkspaceFileTools fileTools,
                                ObjectProvider<ChatStreamService> chatStream,
                                ObjectProvider<ConversationService> conversationService,
                                TransactionTemplate tx) {
        this.api = api;
        this.settings = settings;
        this.bindings = bindings;
        this.conversations = conversations;
        this.members = members;
        this.agents = agents;
        this.messages = messages;
        this.messageService = messageService;
        this.appLogs = appLogs;
        this.downloader = downloader;
        this.fileTools = fileTools;
        this.chatStream = chatStream;
        this.conversationService = conversationService;
        this.tx = tx;
        this.credential = loadCredential();
    }

    // ---------- 设置 ----------

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
                    clamp(s.roundTimeoutMinutes(), 1, 60, 10));
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
                clamp(req.roundTimeoutMinutes(), 1, 60, 10));
        settings.write(SETTINGS_KEY, MAPPER.writeValueAsString(merged));
        return loadSettings();
    }

    private static int clamp(int v, int min, int max, int dft) {
        return v == 0 ? dft : Math.max(min, Math.min(max, v));
    }

    private BotCredential loadCredential() {
        String raw = settings.read(BOT_KEY);
        if (Str.isBlank(raw)) {
            return null;
        }
        try {
            BotCredential c = MAPPER.readValue(raw, BotCredential.class);
            return Str.isBlank(c.token()) ? null : c;
        } catch (Exception e) {
            return null;
        }
    }

    private void saveCredential(BotCredential c) {
        credential = c;
        if (c == null) {
            settings.write(BOT_KEY, "");
        } else {
            settings.write(BOT_KEY, MAPPER.writeValueAsString(c));
        }
    }

    // ---------- 状态与登录 API ----------

    public record Status(boolean connected, boolean enabled, String botId, String ownerUserId,
                         boolean loginInProgress, ChannelSettings settings) {
    }

    public Status status() {
        BotCredential c = credential;
        return new Status(c != null, loadSettings().enabled(),
                c == null ? null : c.botId(), c == null ? null : c.ownerUserId(),
                loginActive, loadSettings());
    }

    public LoginState loginState() {
        return login;
    }

    /** 发起扫码登录：取二维码并后台轮询扫码状态；5 分钟内重复调用返回同一张码 */
    public LoginState startLogin() {
        synchronized (loginLock) {
            if (loginActive) {
                return login;
            }
            loginActive = true;
            pendingVerifyCode = null;
            updateLogin(new LoginState("qr_ready", null, null, null));
            WeixinApiClient.QrCode qr;
            try {
                qr = api.getBotQrcode();
            } catch (Exception e) {
                loginActive = false;
                login = new LoginState("failed", null, "获取二维码失败：" + e.getMessage(), null);
                throw ApiException.badRequest("获取二维码失败：" + e.getMessage());
            }
            updateLogin(new LoginState("qr_ready", QrSvg.render(qr.qrContent()), null, null));
            loginThread = new Thread(() -> loginLoop(qr), "wechat-login");
            loginThread.setDaemon(true);
            loginThread.start();
            return login;
        }
    }

    /** 提交手机微信显示的配对码（need_verifycode 状态下调用） */
    public void submitVerifyCode(String code) {
        if (code == null || code.isBlank()) {
            throw ApiException.badRequest("配对码不能为空");
        }
        pendingVerifyCode = code.trim();
    }

    public void cancelLogin() {
        Thread t = loginThread;
        loginActive = false;
        if (t != null) {
            t.interrupt();
        }
        synchronized (loginLock) {
            login = new LoginState("idle", null, null, null);
        }
    }

    /** 断开连接：通知微信侧停止并清除本机 token（绑定关系保留，重连后消息继续进原会话） */
    public void disconnect() {
        BotCredential c = credential;
        if (c != null) {
            api.notify(false, c.baseUrl(), c.token());
            saveCredential(null);
            updatesBuf = "";
            log(AppLog.TYPE_WECHAT, null, null, "微信通道已断开（" + c.botId() + "）");
        }
        ensurePollThread();
    }

    // ---------- 登录轮询 ----------

    private void updateLogin(LoginState s) {
        login = s;
    }

    @SuppressWarnings("BusyWait")
    private void loginLoop(WeixinApiClient.QrCode qr) {
        String host = WeixinApiClient.FIXED_BASE_URL.replaceFirst("^https://", "");
        long deadline = System.currentTimeMillis() + 5 * 60_000;
        String qrcode = qr.qrcode();
        int refresh = 0;
        try {
            while (loginActive && System.currentTimeMillis() < deadline) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                String code = pendingVerifyCode;
                WeixinApiClient.QrStatus st = api.getQrcodeStatus(host, qrcode, code);
                switch (st.status()) {
                    case "scaned" -> {
                        pendingVerifyCode = null;
                        updateLogin(new LoginState("scaned", login.qrSvg(), null, null));
                    }
                    case "need_verifycode" -> updateLogin(new LoginState("need_verifycode", login.qrSvg(), null, null));
                    case "verify_code_blocked" -> {
                        updateLogin(new LoginState("failed", login.qrSvg(), "配对码错误次数过多，请稍后重试", null));
                        loginActive = false;
                        return;
                    }
                    case "expired" -> {
                        if (++refresh > 3) {
                            updateLogin(new LoginState("failed", null, "二维码多次过期，请重新发起连接", null));
                            loginActive = false;
                            return;
                        }
                        WeixinApiClient.QrCode nq = api.getBotQrcode();
                        qrcode = nq.qrcode();
                        updateLogin(new LoginState("qr_ready", QrSvg.render(nq.qrContent()), null, null));
                    }
                    case "binded_redirect" -> {
                        updateLogin(new LoginState("failed", null, "该微信已绑定其他实例，请先在微信里解绑后再试", null));
                        loginActive = false;
                        return;
                    }
                    case "scaned_but_redirect" -> {
                        if (!Str.isBlank(st.redirectHost())) {
                            host = st.redirectHost();
                        }
                    }
                    case "confirmed" -> {
                        if (Str.isBlank(st.botToken()) || Str.isBlank(st.botId())) {
                            updateLogin(new LoginState("failed", null, "登录失败：服务端未返回完整凭据", null));
                            loginActive = false;
                            return;
                        }
                        BotCredential cred = new BotCredential(st.botToken(), st.botId(),
                                Str.isBlank(st.baseUrl()) ? WeixinApiClient.FIXED_BASE_URL : st.baseUrl(),
                                st.ownerUserId(), java.time.LocalDateTime.now()
                                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
                        saveCredential(cred);
                        updatesBuf = "";
                        updateLogin(new LoginState("confirmed", null, null, st.botId()));
                        loginActive = false;
                        api.notify(true, cred.baseUrl(), cred.token());
                        log(AppLog.TYPE_WECHAT, null, null, "微信通道已连接（" + st.botId() + "）");
                        ensurePollThread();
                        return;
                    }
                    default -> {
                        // wait：继续轮询
                    }
                }
                Thread.sleep(1000);
            }
            if (loginActive) {
                updateLogin(new LoginState("failed", null, "登录超时，请重试", null));
            }
        } catch (InterruptedException e) {
            // 用户取消
        } catch (Exception e) {
            if (loginActive) {
                updateLogin(new LoginState("failed", login.qrSvg(), "登录异常：" + e.getMessage(), null));
            }
        } finally {
            loginActive = false;
        }
    }

    // ---------- 收消息轮询与路由 ----------

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        ensurePollThread();
    }

    private synchronized void ensurePollThread() {
        if (pollThread != null && pollThread.isAlive()) {
            return;
        }
        Thread t = new Thread(this::pollLoop, "wechat-poll");
        t.setDaemon(true);
        t.start();
        pollThread = t;
    }

    @SuppressWarnings("BusyWait")
    private void pollLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                BotCredential cred = credential;
                ChannelSettings s = loadSettings();
                if (cred == null || !s.enabled()) {
                    Thread.sleep(3000);
                    continue;
                }
                WeixinApiClient.Updates u = api.getUpdates(cred.baseUrl(), cred.token(), updatesBuf);
                if (u.errcode() == -14) {
                    log(AppLog.TYPE_ERROR, null, null, "微信会话已超时失效，请到设置页重新扫码连接");
                    saveCredential(null);
                    updatesBuf = "";
                    continue;
                }
                updatesBuf = u.buf();
                for (WeixinApiClient.InboundMessage m : u.messages()) {
                    try {
                        handle(m, cred, s);
                    } catch (Exception e) {
                        log(AppLog.TYPE_ERROR, null, null, "处理微信消息失败：" + message(e));
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                log(AppLog.TYPE_ERROR, null, null, "微信收消息异常：" + message(e));
                sleepQuiet(3000);
            }
        }
    }

    private void handle(WeixinApiClient.InboundMessage m, BotCredential cred, ChannelSettings s) {
        if (Str.isBlank(m.messageId()) || !markSeen(m.messageId())) {
            return;
        }
        // 语音优先用微信侧的转写文本当文字；其余媒体（图片/文件/视频）下载解密落盘
        String text = m.text();
        WeixinApiClient.InboundMedia media = m.media();
        if (media != null && media.type() == WeixinApiClient.MEDIA_VOICE && Str.isBlank(text)
                && !Str.isBlank(media.voiceText())) {
            text = media.voiceText();
            media = null;
        }
        if (Str.isBlank(text) && media == null) {
            boolean voice = m.media() != null && m.media().type() == WeixinApiClient.MEDIA_VOICE;
            log(AppLog.TYPE_WECHAT, null, null, "收到微信"
                    + (voice ? "语音消息（无转写文本）" : "非文本消息（itemType=" + m.itemType() + "）") + "，已提示不支持");
            api.sendText(cred.baseUrl(), cred.token(), m.fromUserId(), m.contextToken(), voice
                    ? "（暂不支持在微信里处理语音消息，请转成文字发送）"
                    : "（暂不支持在微信里处理图片/语音等媒体消息，请在智群桌面应用里发送）");
            return;
        }
        Conversation conv = resolveConversation(m.fromUserId());
        Agent agent = resolveAgent();
        List<ConversationFileGrant> grants = List.of();
        if (media != null) {
            try {
                ConversationFileGrant g = saveMedia(conv, media);
                grants = List.of(g);
                log(AppLog.TYPE_WECHAT, conv.getId(), agent.getId(), "已接收微信媒体：" + g.getName());
            } catch (Exception e) {
                log(AppLog.TYPE_ERROR, conv.getId(), agent.getId(), "微信媒体接收失败：" + message(e));
                if (Str.isBlank(text)) {
                    api.sendText(cred.baseUrl(), cred.token(), m.fromUserId(), m.contextToken(),
                            "（媒体接收失败：" + truncate(message(e), 120) + "，请重试或改用文字发送）");
                    return;
                }
            }
        }
        Message userMsg = messageService.createUserMessage(conv, text, grants);
        log(AppLog.TYPE_WECHAT, conv.getId(), agent.getId(), "收到微信消息，已交给「" + agent.getName() + "」处理");
        ChatStreamService stream = chatStream.getIfAvailable();
        if (stream == null) {
            api.sendText(cred.baseUrl(), cred.token(), m.fromUserId(), m.contextToken(),
                    "（会话服务暂不可用，请稍后再试）");
            return;
        }
        long deadline = System.currentTimeMillis() + s.roundTimeoutMinutes() * 60_000L;
        // 与定时任务相同的重入保护：上一轮未结束先等空闲，避免两轮流交错
        while (stream.isRunning(conv.getId()) && System.currentTimeMillis() < deadline) {
            sleepQuiet(500);
        }
        // 后台回合：无人盯审批，按通道配置自动放行或立即拒绝（默认都不放行），决定记运行日志
        stream.stream(new ConversationStreamSupport.Broadcast(conv.getId()), conv, userMsg,
                new ConversationStreamSupport.TaskTag(null, null, s.autoWrite(), s.autoShell(), false));
        // 等回合真正开跑（executor 异步提交，isRunning 置位在任务线程里；超时兜底防卡死）
        long startWait = System.currentTimeMillis();
        while (!stream.isRunning(conv.getId()) && System.currentTimeMillis() - startWait < 10_000) {
            sleepQuiet(100);
        }
        // 等回合结束再聚合
        while (stream.isRunning(conv.getId()) && System.currentTimeMillis() < deadline) {
            sleepQuiet(500);
        }
        String reply = aggregate(conv, userMsg);
        if (Str.isBlank(reply)) {
            reply = "（本轮没有产生回复，请查看应用内的运行日志）";
        }
        api.sendText(cred.baseUrl(), cred.token(), m.fromUserId(), m.contextToken(), truncate(reply, s.maxReplyChars()));
        log(AppLog.TYPE_WECHAT, conv.getId(), agent.getId(), "已回复微信消息：" + truncate(reply, 100));
    }

    /** 读库收集本轮（用户消息之后）所有智能体文本段，按时间顺序拼接 */
    private String aggregate(Conversation conv, Message userMsg) {
        long from = userMsg.getCreatedAt();
        StringBuilder sb = new StringBuilder();
        for (Message msg : messages.findByConversationIdOrderByCreatedAtAsc(conv.getId())) {
            if (msg.getCreatedAt() < from || !"agent".equals(msg.getSenderType())
                    || !"text".equals(msg.getType()) || Str.isBlank(msg.getContent())) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(msg.getContent());
        }
        return sb.toString();
    }

    /** 媒体落盘到主工作区「微信接收」子目录（沙箱内，文件工具与 view_image 可直接访问），返回消息附件 */
    private ConversationFileGrant saveMedia(Conversation conv, WeixinApiClient.InboundMedia media) throws Exception {
        byte[] data = downloader.fetch(media);
        Path dir = Paths.get(fileTools.primaryRoot(), "微信接收");
        Files.createDirectories(dir);
        String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS")
                .format(java.time.LocalDateTime.now());
        String name;
        if (media.type() == WeixinApiClient.MEDIA_FILE && !Str.isBlank(media.fileName())) {
            name = sanitizeName(media.fileName());
        } else {
            String base = switch (media.type()) {
                case WeixinApiClient.MEDIA_IMAGE -> "微信图片";
                case WeixinApiClient.MEDIA_VIDEO -> "微信视频";
                default -> "微信文件";
            };
            name = base + "_" + stamp + extOf(data);
        }
        Path target = dir.resolve(stamp + "_" + name);
        Files.write(target, data);
        return new ConversationFileGrant(conv.getId(), target.toString(),
                media.type() == WeixinApiClient.MEDIA_IMAGE
                        ? ConversationFileGrant.TYPE_IMAGE : ConversationFileGrant.TYPE_FILE,
                name, System.currentTimeMillis());
    }

    /** 按魔数猜扩展名（微信媒体下载不带文件名；扩展名供前端按 MIME 渲染） */
    private static String extOf(byte[] d) {
        if (d.length >= 3 && (d[0] & 0xff) == 0xFF && (d[1] & 0xff) == 0xD8) return ".jpg";
        if (d.length >= 4 && (d[0] & 0xff) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return ".png";
        if (d.length >= 3 && d[0] == 'G' && d[1] == 'I' && d[2] == 'F') return ".gif";
        if (d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') return ".webp";
        if (d.length >= 12 && d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p') return ".mp4";
        if (d.length >= 2 && d[0] == 'B' && d[1] == 'M') return ".bmp";
        return "";
    }

    private static String sanitizeName(String s) {
        String n = s.replaceAll("[\\\\/:*?\"<>|\r\n\t]", "_").trim();
        if (n.length() > 80) {
            int dot = n.lastIndexOf('.');
            String ext = dot > 0 ? n.substring(dot) : "";
            n = n.substring(0, 80 - ext.length()) + ext;
        }
        return n.isEmpty() ? "file" : n;
    }

    // ---------- 绑定与成员 ----------

    private Conversation resolveConversation(String senderId) {
        Conversation c = tx.execute(status -> {
            WechatBinding b = bindings.findById(senderId).orElse(null);
            if (b != null) {
                Conversation existing = conversations.findById(b.getConversationId()).orElse(null);
                if (existing != null && existing.getArchivedAt() == null) {
                    syncMember(existing);
                    return existing;
                }
                bindings.delete(b);
            }
            Conversation nc = conversationService.getObject().newConversation(
                    CurrentUser.ID, "single", ConversationService.CATEGORY_CHAT, "微信ClawBot", "wechat");
            members.save(new ConversationMember(nc.getId(), resolveAgent().getId(), System.currentTimeMillis()));
            WechatBinding nb = new WechatBinding();
            nb.setSenderUserId(senderId);
            nb.setConversationId(nc.getId());
            nb.setCreatedAt(System.currentTimeMillis());
            bindings.save(nb);
            log(AppLog.TYPE_WECHAT, nc.getId(), null, "微信用户 " + shortId(senderId) + " 已绑定新会话");
            return nc;
        });
        return c;
    }

    /** 处理智能体变更即时生效：单聊成员与配置不一致时替换成员行 */
    private void syncMember(Conversation c) {
        Agent agent = resolveAgent();
        List<ConversationMember> ms = members.findByConversationIdOrderByCreatedAtAsc(c.getId());
        if (ms.size() == 1 && ms.get(0).getAgentId().equals(agent.getId())) {
            return;
        }
        members.deleteByConversationId(c.getId());
        members.save(new ConversationMember(c.getId(), agent.getId(), System.currentTimeMillis()));
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

    private static String shortId(String senderId) {
        String s = senderId.replace("@im.wechat", "");
        return s.length() <= 8 ? s : s.substring(s.length() - 8);
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private void log(String type, String conversationId, String agentId, String content) {
        appLogs.record(type, conversationId, agentId, content);
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
        BotCredential c = credential;
        if (c != null) {
            api.notify(false, c.baseUrl(), c.token());
        }
        Thread t = pollThread;
        if (t != null) {
            t.interrupt();
        }
        Thread lt = loginThread;
        if (lt != null) {
            lt.interrupt();
        }
    }
}
