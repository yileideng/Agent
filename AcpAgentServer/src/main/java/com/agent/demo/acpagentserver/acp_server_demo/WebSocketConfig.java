package com.agent.demo.acpagentserver.acp_server_demo;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 对 Gateway 暴露的 ACP WebSocket 入口：{@code /acp}。
 *
 * <p>外部 ACP Server 的地址就是 {@code ws://host:8095/acp}。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final AcpWebSocketHandler handler;

    public WebSocketConfig(AcpWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/acp").setAllowedOriginPatterns("*");
    }
}
