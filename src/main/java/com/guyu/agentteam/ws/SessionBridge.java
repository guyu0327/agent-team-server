package com.guyu.agentteam.ws;

import com.guyu.agentteam.common.Json;
import com.guyu.agentteam.dto.XfyunAsrConfigDto;
import com.guyu.agentteam.service.AsrStreamService;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 一条前端连接对应的桥接会话：把收到的 PCM 按讯飞节奏（40ms/1280B）装帧转发，
 * 把识别结果转成 {type:partial|final|error|end} 回传。所有清理路径都收敛到幂等的 shutdown()。
 */
class SessionBridge {

    /** 40ms @ 16kHz 16bit 单声道 */
    private static final int FRAME_BYTES = 1280;
    private static final long PACING_MS = 40;
    private static final long MAX_SECONDS = 60;
    /** 队列上限 ≈ 1.3s 音频；满则丢最旧，保延迟不保完整 */
    private static final int QUEUE_LIMIT = 32;

    private static final ObjectMapper MAPPER = Json.mapper();

    private final WebSocketSession client;
    private final AsrStreamService streamService;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(QUEUE_LIMIT);
    private final AtomicBoolean done = new AtomicBoolean();
    private final AtomicBoolean finishing = new AtomicBoolean();
    private final Object sendLock = new Object();

    private XfyunAsrConfigDto cfg;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> pacer;
    private ScheduledFuture<?> maxTimer;
    private volatile WebSocket upstream;
    /** 讯飞侧要求按序发送，用链式 future 串行化 sendText */
    private volatile CompletableFuture<WebSocket> sendChain;
    private boolean firstFrameSent;
    /** 当前段累积文本：讯飞无动态修正时逐帧返回增量词，段结束（status==2）转 final */
    private final StringBuilder segmentBuf = new StringBuilder();

    SessionBridge(WebSocketSession client, AsrStreamService streamService) {
        this.client = client;
        this.streamService = streamService;
    }

    void start() {
        cfg = streamService.getConfig();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        maxTimer = scheduler.schedule(this::requestFinish, MAX_SECONDS, TimeUnit.SECONDS);
        CompletableFuture
                .supplyAsync(() -> streamService.buildWsUrl(cfg))
                .thenCompose(url -> httpClient.newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .buildAsync(url, new UpstreamListener()))
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        clientError("无法连接转写服务：" + rootMessage(err));
                        return;
                    }
                    if (done.get()) {
                        ws.abort();
                        return;
                    }
                    upstream = ws;
                    pacer = scheduler.scheduleWithFixedDelay(this::drainOneFrame, PACING_MS, PACING_MS, TimeUnit.MILLISECONDS);
                });
    }

    /** 前端音频入队；结束后丢弃 */
    void feed(byte[] bytes) {
        if (done.get() || finishing.get()) {
            return;
        }
        while (!queue.offer(bytes)) {
            byte[] dropped = queue.poll();
            if (dropped == null) {
                return;
            }
            if (dropped.length == 0) {
                // 挤到结束哨兵：还回去、丢当前帧——哨兵被挤掉后结束帧丢失，只能等 60s 上限兜底
                queue.offer(dropped);
                return;
            }
        }
    }

    /** 用户停止 / 60s 上限：排空残余后向讯飞发结束帧，等结果回完再 end */
    void requestFinish() {
        if (finishing.compareAndSet(false, true)) {
            if (maxTimer != null) {
                maxTimer.cancel(false);
            }
            byte[] sentinel = new byte[0];
            while (!queue.offer(sentinel)) {
                queue.poll();
            }
        }
    }

    void shutdown() {
        if (!done.compareAndSet(false, true)) {
            return;
        }
        if (pacer != null) {
            pacer.cancel(false);
        }
        if (maxTimer != null) {
            maxTimer.cancel(false);
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        WebSocket ws = upstream;
        if (ws != null) {
            try {
                ws.abort();
            } catch (Exception ignored) {
                // 上游已断
            }
        }
        try {
            if (client.isOpen()) {
                client.close(CloseStatus.NORMAL);
            }
        } catch (Exception ignored) {
            // 前端已断
        }
    }

    private void drainOneFrame() {
        WebSocket ws = upstream;
        if (ws == null || done.get()) {
            return;
        }
        byte[] chunk = queue.poll();
        if (chunk == null) {
            return;
        }
        if (chunk.length == 0) {
            sendFinishFrame();
            return;
        }
        sendJson(firstFrameSent ? continueFrame(chunk) : firstFrame(chunk));
        firstFrameSent = true;
    }

    private String firstFrame(byte[] chunk) {
        ObjectNode root = MAPPER.createObjectNode();
        root.putObject("common").put("app_id", cfg.appId());
        root.putObject("business")
                .put("language", "zh_cn")
                .put("domain", "iat")
                .put("accent", "mandarin")
                .put("eos", 10000);
        root.set("data", audioData(chunk, 0));
        return root.toString();
    }

    private String continueFrame(byte[] chunk) {
        ObjectNode root = MAPPER.createObjectNode();
        root.set("data", audioData(chunk, 1));
        return root.toString();
    }

    private String sendFinishFrame() {
        ObjectNode root = MAPPER.createObjectNode();
        root.putObject("common").put("app_id", cfg.appId());
        root.putObject("business")
                .put("language", "zh_cn")
                .put("domain", "iat")
                .put("accent", "mandarin")
                .put("eos", 10000);
        root.set("data", audioData(new byte[0], 2));
        cancelPacer();
        sendJson(root.toString());
        return "";
    }

    private ObjectNode audioData(byte[] chunk, int status) {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("status", status);
        data.put("format", "audio/L16;rate=16000");
        data.put("encoding", "raw");
        data.put("audio", Base64.getEncoder().encodeToString(chunk));
        return data;
    }

    private void cancelPacer() {
        if (pacer != null) {
            pacer.cancel(false);
        }
    }

    private void sendJson(String json) {
        synchronized (sendLock) {
            WebSocket ws = upstream;
            if (ws == null || done.get()) {
                return;
            }
            CompletableFuture<WebSocket> prev = sendChain != null
                    ? sendChain
                    : CompletableFuture.completedFuture(null);
            sendChain = prev.thenCompose(v -> ws.sendText(json, true))
                    .exceptionally(e -> {
                        shutdown();
                        return null;
                    });
        }
    }

    private void handleUpstreamText(String json) {
        JsonNode root = MAPPER.readTree(json);
        int code = root.path("code").asInt(0);
        if (code != 0) {
            clientError(upstreamErrorMessage(code, root.path("message").asString("")));
            return;
        }
        JsonNode data = root.path("data");
        StringBuilder sb = new StringBuilder();
        for (JsonNode word : data.path("result").path("ws")) {
            for (JsonNode cw : word.path("cw")) {
                sb.append(cw.path("w").asString(""));
            }
        }
        boolean segEnd = data.path("status").asInt(0) == 2;
        if (!segEnd) {
            segmentBuf.append(sb);
            if (!segmentBuf.isEmpty()) {
                clientEvent("partial", segmentBuf.toString());
            }
            return;
        }
        segmentBuf.append(sb);
        clientEvent("final", segmentBuf.toString());
        segmentBuf.setLength(0);
        if (finishing.get()) {
            clientEnd();
            shutdown();
        }
    }

    private void clientEvent(String type, String text) {
        synchronized (sendLock) {
            try {
                if (!client.isOpen() || done.get()) {
                    return;
                }
                ObjectNode msg = MAPPER.createObjectNode();
                msg.put("type", type);
                msg.put("text", text);
                client.sendMessage(new TextMessage(msg.toString()));
            } catch (Exception e) {
                shutdown();
            }
        }
    }

    private void clientError(String message) {
        synchronized (sendLock) {
            try {
                if (!client.isOpen() || done.get()) {
                    return;
                }
                ObjectNode msg = MAPPER.createObjectNode();
                msg.put("type", "error");
                msg.put("message", message);
                client.sendMessage(new TextMessage(msg.toString()));
            } catch (Exception ignored) {
                // 前端已断
            }
        }
        shutdown();
    }

    private void clientEnd() {
        synchronized (sendLock) {
            try {
                if (!client.isOpen() || done.get()) {
                    return;
                }
                client.sendMessage(new TextMessage("{\"type\":\"end\"}"));
                client.close(CloseStatus.NORMAL);
            } catch (Exception ignored) {
                // 前端已断
            }
        }
    }

    private String upstreamErrorMessage(int code, String raw) {
        return switch (code) {
            case 10005, 10105, 10106, 10107, 10108, 10109, 10110 ->
                    "鉴权失败（" + code + "）：请检查 appId/apiKey/apiSecret 是否正确、应用是否开通语音听写，本机时间偏差需在 5 分钟内";
            case 11200 -> "转写服务不可用（11200）：今日免费额度已用完，或语音听写服务未授权/已过期";
            case 11201, 11202 -> "转写请求过于频繁（" + code + "），请稍后再试";
            case 10313 -> "appId 为空或无效（10313），请检查设置";
            case 10114, 10014 -> "转写会话超时（" + code + "），请重试";
            default -> "转写服务错误 " + code + (raw.isBlank() ? "" : "：" + raw);
        };
    }

    private String rootMessage(Throwable err) {
        Throwable cur = err;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() != null ? cur.getMessage() : cur.getClass().getSimpleName();
    }

    private class UpstreamListener implements WebSocket.Listener {

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            try {
                handleUpstreamText(data.toString());
            } catch (Exception e) {
                clientError("解析转写结果失败：" + e.getMessage());
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            clientEnd();
            shutdown();
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            clientError("转写连接中断：" + (error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName()));
        }
    }
}
