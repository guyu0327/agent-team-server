package com.guyu.agentteam.ws;

import com.guyu.agentteam.service.AsrStreamService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实时语音转写桥接：前端 WS（/api/asr/stream）↔ 讯飞流式听写。
 * 前端发二进制 16k PCM 音频与 {"type":"stop"} 控制帧；后端按讯飞节奏装帧转发，
 * 识别结果转成 {type:partial|final|error|end} JSON 回传。每个前端连接一个 SessionBridge。
 */
@Component
public class AsrStreamHandler extends AbstractWebSocketHandler {

    private static final String BRIDGE_KEY = "asrBridge";

    private final AsrStreamService streamService;

    public AsrStreamHandler(AsrStreamService streamService) {
        this.streamService = streamService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (!streamService.isConfigured()) {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(
                        "{\"type\":\"error\",\"message\":\"实时识别未配置，请先到设置页填写讯飞接口参数\"}"));
                session.close(CloseStatus.POLICY_VIOLATION);
            }
            return;
        }
        SessionBridge bridge = new SessionBridge(session, streamService);
        session.getAttributes().put(BRIDGE_KEY, bridge);
        bridge.start();
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        SessionBridge bridge = bridgeOf(session);
        if (bridge == null) {
            return;
        }
        ByteBuffer buf = message.getPayload();
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        bridge.feed(bytes);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        SessionBridge bridge = bridgeOf(session);
        if (bridge != null && message.getPayload().contains("\"stop\"")) {
            bridge.requestFinish();
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SessionBridge bridge = bridgeOf(session);
        if (bridge != null) {
            bridge.shutdown();
        }
    }

    private SessionBridge bridgeOf(WebSocketSession session) {
        return (SessionBridge) session.getAttributes().get(BRIDGE_KEY);
    }
}
