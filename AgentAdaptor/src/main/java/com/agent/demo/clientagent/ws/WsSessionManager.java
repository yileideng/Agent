package com.agent.demo.clientagent.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 移动端客户端的 WebSocket 会话管理：负责 WS 连接注册、工具调用推送与结果匹配。
 *
 * <p>与 {@code ClientRegistry} 的分工：
 * <ul>
 *   <li>{@code ClientRegistry} 决定"这个客户端用什么传输方式"</li>
 *   <li>{@code WsSessionManager} 决定"这次工具调用推给哪条连接"</li>
 * </ul>
 *
 * <p>路由优先级：{@code (clientId, endUserId, sessionId)} 精确匹配 → 该用户唯一连接 → 报错。
 * 按 {@code endUserId} 隔离，保证不同终端用户的工具调用不会串到别人的设备上。
 */
@Component
public class WsSessionManager {

    private static final String DEFAULT_END_USER = "default";

    /** 一条已建立的 WebSocket 连接 */
    public record ConnectionInfo(String connectionId,
                                 String clientId,
                                 String endUserId,
                                 String deviceId,
                                 String sessionId,
                                 WebSocketSession session) {
    }

    /** connectionId -> 连接信息 */
    private final ConcurrentMap<String, ConnectionInfo> connections = new ConcurrentHashMap<>();

    /** clientId|endUserId -> connectionId 集合 */
    private final ConcurrentMap<String, Set<String>> userConnections = new ConcurrentHashMap<>();

    /** callId -> 等待结果的 Future */
    private final ConcurrentMap<String, CompletableFuture<String>> pendingCalls = new ConcurrentHashMap<>();

    /** callId -> 该调用被推送到的 connectionId，用于连接断开时快速失败 */
    private final ConcurrentMap<String, String> callOwners = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final long toolTimeoutMillis;

    public WsSessionManager(@Value("${agent.ws.tool-timeout-ms:60000}") long toolTimeoutMillis) {
        this.toolTimeoutMillis = Math.max(1000, toolTimeoutMillis);
    }

    // ── 连接管理 ──

    /**
     * 注册一条新的 WS 连接，返回连接ID。
     * sessionId 可选：客户端若已知会话ID可提前绑定，便于精确路由。
     */
    public String register(WebSocketSession session,
                           String clientId,
                           String endUserId,
                           String deviceId,
                           String sessionId) {
        String connectionId = session.getId();
        String uid = normalize(endUserId);
        ConnectionInfo info = new ConnectionInfo(connectionId, clientId, uid, deviceId, sessionId, session);
        connections.put(connectionId, info);
        userConnections.computeIfAbsent(userKey(clientId, uid), ignored -> ConcurrentHashMap.newKeySet())
                .add(connectionId);
        System.out.println("[WsSessionManager] 移动端连接建立 " + connectionId
                + "，clientId=" + clientId + "，endUserId=" + uid + "，deviceId=" + deviceId);
        return connectionId;
    }

    /**
     * 连接断开：移除索引，并让该连接上未完成的工具调用立即失败，避免Agent无限等待。
     */
    public void unregister(String connectionId) {
        ConnectionInfo info = connections.remove(connectionId);
        if (info == null) {
            return;
        }
        String key = userKey(info.clientId(), info.endUserId());
        Set<String> ids = userConnections.get(key);
        if (ids != null) {
            ids.remove(connectionId);
            if (ids.isEmpty()) {
                userConnections.remove(key);
            }
        }
        callOwners.entrySet().stream()
                .filter(entry -> connectionId.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList()
                .forEach(callId -> {
                    CompletableFuture<String> future = pendingCalls.get(callId);
                    if (future != null) {
                        future.completeExceptionally(
                                new IllegalStateException("移动端客户端连接已断开: " + connectionId));
                    }
                });
        System.out.println("[WsSessionManager] 移动端连接断开 " + connectionId
                + "，clientId=" + info.clientId() + "，endUserId=" + info.endUserId());
    }

    public int connectionCount() {
        return connections.size();
    }

    // ── 工具调用 ──

    /**
     * 向移动端客户端推送工具调用并等待结果。
     *
     * @return 客户端返回的工具结果（JSON 文本）
     */
    public String invoke(String clientId,
                         String endUserId,
                         String sessionId,
                         String toolName,
                         Map<String, Object> arguments) {
        ConnectionInfo target = resolve(clientId, endUserId, sessionId);
        String callId = UUID.randomUUID().toString();
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingCalls.put(callId, future);
        callOwners.put(callId, target.connectionId());

        ObjectNode message = objectMapper.createObjectNode();
        message.put("type", "tool_call");
        message.put("callId", callId);
        if (sessionId != null && !sessionId.isBlank()) {
            message.put("sessionId", sessionId);
        }
        message.put("tool", toolName);
        message.set("arguments", toJsonObject(arguments));

        try {
            send(target.session(), message);
            System.out.println("[WsSessionManager] 推送工具调用 " + toolName + " 到连接 "
                    + target.connectionId() + "，callId=" + callId + "，参数: " + arguments);
            String result = future.get(toolTimeoutMillis, TimeUnit.MILLISECONDS);
            System.out.println("[WsSessionManager] 收到工具结果 callId=" + callId + ": " + result);
            return result;
        } catch (TimeoutException e) {
            throw new IllegalStateException("等待移动端工具执行结果超时(" + toolTimeoutMillis + "ms): " + toolName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待移动端工具执行结果被中断: " + toolName, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("移动端工具执行失败(" + toolName + "): " + cause.getMessage(), cause);
        } finally {
            pendingCalls.remove(callId);
            callOwners.remove(callId);
        }
    }

    /**
     * 客户端回传工具结果：按 callId 唤醒等待中的调用。
     */
    public void completeToolResult(String callId, JsonNode result, String error) {
        CompletableFuture<String> future = pendingCalls.get(callId);
        if (future == null) {
            System.out.println("[WsSessionManager] 收到未知 callId 的工具结果，已忽略: " + callId);
            return;
        }
        if (error != null && !error.isBlank()) {
            future.completeExceptionally(new IllegalStateException(error));
            return;
        }
        future.complete(toResultText(result));
    }

    // ── 内部实现 ──

    /**
     * 解析目标连接：sessionId 精确匹配优先；否则该终端用户只有一条连接时直接使用。
     */
    private ConnectionInfo resolve(String clientId, String endUserId, String sessionId) {
        String key = userKey(clientId, normalize(endUserId));
        Set<String> ids = userConnections.get(key);
        if (ids == null || ids.isEmpty()) {
            throw new IllegalStateException("移动端客户端未连接: clientId=" + clientId
                    + "，endUserId=" + normalize(endUserId));
        }
        List<ConnectionInfo> candidates = new ArrayList<>();
        for (String id : ids) {
            ConnectionInfo info = connections.get(id);
            if (info != null) {
                candidates.add(info);
            }
        }
        if (candidates.isEmpty()) {
            throw new IllegalStateException("移动端客户端未连接: clientId=" + clientId
                    + "，endUserId=" + normalize(endUserId));
        }
        if (sessionId != null && !sessionId.isBlank()) {
            Optional<ConnectionInfo> sessionMatch = candidates.stream()
                    .filter(info -> sessionId.equals(info.sessionId()))
                    .findFirst();
            if (sessionMatch.isPresent()) {
                return sessionMatch.get();
            }
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        throw new IllegalStateException("同一终端用户存在多条移动端连接，无法确定目标设备"
                + "(clientId=" + clientId + "，endUserId=" + normalize(endUserId)
                + ")，请为会话绑定 sessionId 或指定 deviceId");
    }

    private void send(WebSocketSession session, ObjectNode message) {
        if (!session.isOpen()) {
            throw new IllegalStateException("WebSocket连接已关闭: " + session.getId());
        }
        try {
            String payload = objectMapper.writeValueAsString(message);
            // WebSocketSession 不保证并发写安全，这里按连接加锁
            synchronized (session) {
                session.sendMessage(new TextMessage(payload));
            }
        } catch (IOException e) {
            throw new IllegalStateException("向移动端推送消息失败: " + e.getMessage(), e);
        }
    }

    private ObjectNode toJsonObject(Map<String, Object> arguments) {
        ObjectNode node = objectMapper.createObjectNode();
        if (arguments != null) {
            arguments.forEach((key, value) -> node.put(key, value == null ? null : String.valueOf(value)));
        }
        return node;
    }

    private String toResultText(JsonNode result) {
        if (result == null || result.isNull()) {
            return "";
        }
        return result.isTextual() ? result.asText() : result.toString();
    }

    private String userKey(String clientId, String endUserId) {
        return clientId + "|" + normalize(endUserId);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? DEFAULT_END_USER : value.trim();
    }
}
