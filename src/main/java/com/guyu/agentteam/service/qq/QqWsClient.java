package com.guyu.agentteam.service.qq;

import com.guyu.agentteam.common.Json;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * QQ 官方 WSS 网关客户端（op 协议对照 bot.q.qq.com/wiki，零外部依赖）：
 * 连接后收 Hello(op10)→回 Identify(op2) 或 Resume(op6)→READY/RESUMED(op0)，
 * 之后按心跳间隔发 op1（d=最后序列号）。事件为 op0 dispatch（t=事件名）。
 * 断线指数退避重连，优先 Resume（平台补发离线事件），Resume 失败（连接再断）则丢弃会话重新 Identify。
 */
public class QqWsClient implements WebSocket.Listener {

    public interface DispatchListener {
        void onDispatch(String type, JsonNode data);
    }

    public interface StateListener {
        void onStateChange(boolean connected, String error);
    }

    private static final String GATEWAY = "wss://api.sgroup.qq.com/websocket";
    /** intents bit 25 = C2C 私聊消息 + 群聊 @ 消息事件 */
    private static final int INTENTS = 1 << 25;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final Supplier<String> tokenSupplier;
    private final DispatchListener dispatchListener;
    private final StateListener stateListener;

    private final Object sendLock = new Object();
    private final StringBuilder textBuf = new StringBuilder();

    private WebSocket ws;
    private volatile boolean running;
    private volatile boolean connected;
    private volatile long heartbeatIntervalMs = 30_000;
    private volatile long lastSeq = -1;
    private String sessionId;
    /** 已发 Resume 但尚未收到 RESUMED：期间再断说明会话已失效，重连须改走 Identify */
    private boolean resumePending;
    private ScheduledExecutorService heartbeat;
    private Thread reconnectThread;
    private int reconnectAttempt;

    public QqWsClient(Supplier<String> tokenSupplier, DispatchListener dispatchListener,
                      StateListener stateListener) {
        this.tokenSupplier = tokenSupplier;
        this.dispatchListener = dispatchListener;
        this.stateListener = stateListener;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        reconnectAttempt = 0;
        connect();
    }

    public synchronized void stop() {
        running = false;
        stopHeartbeat();
        WebSocket w = ws;
        ws = null;
        if (w != null) {
            try {
                w.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            } catch (Exception ignored) {
                // 连接可能已死
            }
        }
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isConnected() {
        return connected;
    }

    // ---------- 连接 ----------

    private void connect() {
        if (!running) {
            return;
        }
        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(GATEWAY), this)
                .whenComplete((w, err) -> {
                    if (err != null) {
                        scheduleReconnect("连接失败：" + rootMessage(err));
                    }
                });
    }

    private void scheduleReconnect(String why) {
        connected = false;
        stopHeartbeat();
        if (!running) {
            return;
        }
        stateListener.onStateChange(false, why);
        int attempt = ++reconnectAttempt;
        long delay = Math.min(30_000, 3000L * attempt);
        if (reconnectThread != null && reconnectThread.isAlive()) {
            return;
        }
        reconnectThread = new Thread(() -> {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (running) {
                connect();
            }
        }, "qq-ws-reconnect");
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    // ---------- WebSocket.Listener ----------

    @Override
    public void onOpen(WebSocket webSocket) {
        ws = webSocket;
        textBuf.setLength(0);
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        textBuf.append(data);
        if (last) {
            String full = textBuf.toString();
            textBuf.setLength(0);
            try {
                handle(full);
            } catch (Exception ignored) {
                // 单条消息解析失败不拖垮连接
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        if (resumePending) {
            // 会话失效：丢弃 session/seq，下次走全新 Identify
            resumePending = false;
            sessionId = null;
            lastSeq = -1;
        }
        scheduleReconnect("连接被关闭（" + statusCode + (reason == null || reason.isBlank() ? "" : " " + reason) + "）");
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        scheduleReconnect("连接异常：" + rootMessage(error));
    }

    // ---------- op 协议 ----------

    private void handle(String raw) {
        JsonNode msg = Json.mapper().readTree(raw);
        int op = msg.path("op").asInt(-1);
        JsonNode d = msg.path("d");
        if (msg.hasNonNull("s")) {
            lastSeq = msg.path("s").asLong(lastSeq);
        }
        switch (op) {
            case 10 -> { // Hello：先回 Identify/Resume，再按平台给的间隔起心跳
                heartbeatIntervalMs = d.path("heartbeat_interval").asLong(30_000);
                sendIdentifyOrResume();
                startHeartbeat();
            }
            case 0 -> { // Dispatch
                String t = msg.path("t").asText("");
                if ("READY".equals(t)) {
                    sessionId = d.path("session_id").asText(null);
                    resumePending = false;
                    reconnectAttempt = 0;
                    setConnected(true, null);
                } else if ("RESUMED".equals(t)) {
                    resumePending = false;
                    reconnectAttempt = 0;
                    setConnected(true, null);
                } else if (!t.isEmpty()) {
                    dispatchListener.onDispatch(t, d);
                }
            }
            case 11 -> {
                // Heartbeat ACK
            }
            default -> {
                // op9（Invalid Session）等：交给随后的 close 走重连
            }
        }
    }

    private void sendIdentifyOrResume() {
        String token = "QQBot " + tokenSupplier.get();
        if (sessionId != null && lastSeq >= 0) {
            resumePending = true;
            send(Json.mapper().writeValueAsString(Map.of("op", 6, "d", Map.of(
                    "token", token, "session_id", sessionId, "seq", lastSeq))));
        } else {
            send(Json.mapper().writeValueAsString(Map.of("op", 2, "d", Map.of(
                    "token", token, "intents", INTENTS, "shard", List.of(0, 1)))));
        }
    }

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "qq-ws-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleAtFixedRate(() -> {
            if (ws != null) {
                send("{\"op\":1,\"d\":" + (lastSeq >= 0 ? lastSeq : "null") + "}");
            }
        }, heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeat() {
        ScheduledExecutorService h = heartbeat;
        heartbeat = null;
        if (h != null) {
            h.shutdownNow();
        }
    }

    /** Java HttpClient 的 WebSocket 同一时刻只允许一条未完成文本帧，串行化发送 */
    private void send(String payload) {
        WebSocket w = ws;
        if (w == null) {
            return;
        }
        synchronized (sendLock) {
            try {
                w.sendText(payload, true).join();
            } catch (Exception ignored) {
                // 发送失败交由 onError/onClose 走重连
            }
        }
    }

    private void setConnected(boolean value, String error) {
        boolean was = connected;
        connected = value;
        if (was != value || value) {
            stateListener.onStateChange(value, error);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getMessage() == null ? c.toString() : c.getMessage();
    }
}
