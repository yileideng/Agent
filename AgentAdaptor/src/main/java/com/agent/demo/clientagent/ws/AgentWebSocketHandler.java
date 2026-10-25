package com.agent.demo.clientagent.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 移动端客户端接入端点：{@code /ws/agent}。
 *
 * <p>连接握手参数：
 * <ul>
 *   <li>{@code clientId}：客户端标识（必填），对应已注册的 MOBILE 客户端</li>
 *   <li>{@code endUserId}：终端用户标识，用于用户级隔离（可选，缺省 default）</li>
 *   <li>{@code deviceId}：设备标识（可选，用于日志与多设备排查）</li>
 *   <li>{@code sessionId}：会话标识（可选，用于精确路由）</li>
 * </ul>
 *
 * <p>消息协议（JSON）：
 * <ul>
 *   <li>服务端 → 客户端：{@code {"type":"tool_call","callId","sessionId","tool","arguments"}}</li>
 *   <li>客户端 → 服务端：{@code {"type":"tool_result","callId","result"|"error"}}</li>
 *   <li>心跳：客户端 {@code {"type":"ping"}}，服务端回 {@code {"type":"pong"}}</li>
 * </ul>
 */
@Component
public class AgentWebSocketHandler extends TextWebSocketHandler {

    private final WsSessionManager wsSessionManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentWebSocketHandler(WsSessionManager wsSessionManager) {
        this.wsSessionManager = wsSessionManager;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Map<String, String> params = queryParams(session);
        String clientId = params.getOrDefault("clientId", "");
        if (clientId.isBlank()) {
            System.out.println("[Ws] 连接缺少 clientId，拒绝: " + session.getId());
            session.close(CloseStatus.BAD_DATA);
            return;
        }
        String connectionId = wsSessionManager.register(
                session,
                clientId,
                params.get("endUserId"),
                params.get("deviceId"),
                params.get("sessionId"));

        ObjectNode ack = objectMapper.createObjectNode();
        ack.put("type", "connected");
        ack.put("connectionId", connectionId);
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(ack)));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode node = objectMapper.readTree(message.getPayload());
        String type = node.path("type").asText("");
        switch (type) {
            case "tool_result" -> wsSessionManager.completeToolResult(
                    node.path("callId").asText(""),
                    node.get("result"),
                    node.path("error").asText(null));
            case "ping" -> {
                ObjectNode pong = objectMapper.createObjectNode();
                pong.put("type", "pong");
                synchronized (session) {
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(pong)));
                }
            }
            default -> System.out.println("[Ws] 收到未知消息类型: " + type + "，连接: " + session.getId());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        wsSessionManager.unregister(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        System.out.println("[Ws] 连接异常 " + session.getId() + ": " + exception.getMessage());
    }

    /**
     * 解析握手 URL 上的查询参数，避免依赖特定 Spring 工具类的编码行为
     */
    private Map<String, String> queryParams(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null || uri.getRawQuery() == null || uri.getRawQuery().isEmpty()) {
            return Map.of();
        }
        Map<String, String> params = new HashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            int index = pair.indexOf('=');
            if (index <= 0) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8);
            params.put(key, value);
        }
        return params;
    }
}
