package com.guyu.agentteam.config;

import com.guyu.agentteam.ws.AsrStreamHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** 实时语音转写的 WebSocket 端点：/api/asr/stream（前端经 vite ws 代理访问） */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final AsrStreamHandler asrStreamHandler;

    public WebSocketConfig(AsrStreamHandler asrStreamHandler) {
        this.asrStreamHandler = asrStreamHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(asrStreamHandler, "/api/asr/stream").setAllowedOrigins("*");
    }
}
