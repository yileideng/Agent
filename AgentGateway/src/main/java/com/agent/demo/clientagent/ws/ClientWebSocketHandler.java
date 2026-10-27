package com.agent.demo.clientagent.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.UUID;

/**
 * 客户端 WebSocket 接入端点：{@code /ws/agent}。
 *
 * <p>只支持 CAP v0.1 信封：
 * <ul>
 *   <li>客户端连接后先发 {@code hello}，服务端回 {@code hello_ack} 并绑定连接；</li>
 *   <li>服务端下发 {@code tool_call} 信封，客户端回 {@code tool_result} 信封；</li>
 *   <li>{@code ping} / {@code pong} 心跳。</li>
 * </ul>
 */
@Component
public class ClientWebSocketHandler extends TextWebSocketHandler {

    /**
     * 所有 WS 消息的统一信封。
     *
     * @param v       协议版本，固定 "0.1"
     * @param id      消息 ID
     * @param type    hello / tool_result / ping / pong
     * @param payload 消息负载
     */
    public record WsEnvelope(String v, String id, String type, WsPayload payload) {
    }

    /**
     * hello / tool_result / ping 共用的 payload。
     *
     * <p>不同 type 只使用其中一部分字段：
     * hello 用 clientId/endUserId/sessionId/token；
     * tool_result 用 callId/chatId/status/result/error。
     *
     * @param clientId  客户端标识（hello）
     * @param endUserId 终端用户标识（hello）
     * @param sessionId 可选会话 ID（hello）
     * @param token     预留鉴权令牌（hello）
     * @param callId    工具调用 ID（tool_result）
     * @param chatId    本轮对话 ID（tool_result）
     * @param status    success / error（tool_result）
     * @param result    工具结果 JSON（tool_result）
     * @param error     工具错误（tool_result）
     */
    public record WsPayload(String clientId,
                            String endUserId,
                            String sessionId,
                            String token,
                            String callId,
                            String chatId,
                            String status,
                            JsonNode result,
                            WsError error) {
    }

    /**
     * 工具错误。
     *
     * @param code    错误码，如 TOOL_FAILED
     * @param message 可读错误信息
     */
    public record WsError(String code, String message) {
    }

    /** 连接注册与工具调用路由。 */
    private final WsSessionManager wsSessionManager;
    /** JSON 编解码（WebSocket 帧不是 Spring MVC 请求，手动处理）。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ClientWebSocketHandler(WsSessionManager wsSessionManager) {
        this.wsSessionManager = wsSessionManager;
    }

    /**
     * 处理客户端消息：hello 绑定连接、tool_result 唤醒等待中的调用、ping 回 pong。
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        WsEnvelope envelope = objectMapper.readValue(message.getPayload(), WsEnvelope.class);
        WsPayload body = envelope.payload();
        if (body == null) {
            System.out.println("[Ws] 消息缺少 payload，忽略: " + message.getPayload());
            return;
        }

        String type = envelope.type() == null ? "" : envelope.type();
        switch (type) {
            case "hello" -> handleHello(session, body);
            case "tool_result" -> wsSessionManager.completeToolResult(
                    body.callId(), body.result(), errorOf(body));
            case "ping" -> sendEnvelope(session, "pong", objectMapper.createObjectNode());
            default -> System.out.println("[Ws] 收到未知消息类型: " + type
                    + "，连接: " + session.getId());
        }
    }

    /**
     * 把当前连接绑定到 (clientId, endUserId)，后续工具调用按这个身份路由。
     */
    private void handleHello(WebSocketSession session, WsPayload body) throws Exception {
        String clientId = body.clientId() == null ? "" : body.clientId();
        if (clientId.isBlank()) {
            sendError(session, "AUTH_FAILED", "hello 缺少 clientId");
            session.close(CloseStatus.BAD_DATA);
            return;
        }
        String connectionId = wsSessionManager.register(
                session, clientId, body.endUserId(), body.sessionId());

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("connectionId", connectionId);
        payload.put("clientId", clientId);
        payload.put("serverVersion", "0.1");
        sendEnvelope(session, "hello_ack", payload);
    }

    /**
     * 从 tool_result.payload 中提取错误信息；status=success 时返回 null。
     */
    private String errorOf(WsPayload body) {
        if (!"error".equals(body.status())) {
            return null;
        }
        return body.error() == null || body.error().message() == null
                ? "工具执行失败" : body.error().message();
    }

    private void sendEnvelope(WebSocketSession session, String type, ObjectNode payload) throws Exception {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("v", "0.1");
        node.put("id", UUID.randomUUID().toString());
        node.put("type", type);
        node.set("payload", payload);
        synchronized (session) {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(node)));
        }
    }

    private void sendError(WebSocketSession session, String code, String message) throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("code", code);
        payload.put("message", message);
        sendEnvelope(session, "error", payload);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        wsSessionManager.unregister(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        System.out.println("[Ws] 连接异常 " + session.getId() + ": " + exception.getMessage());
    }
}
