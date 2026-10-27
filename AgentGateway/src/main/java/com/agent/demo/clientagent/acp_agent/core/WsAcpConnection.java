package com.agent.demo.clientagent.acp_agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * ACP WebSocket 传输：连接外部 ACP Server，每条 text frame 放一个 JSON-RPC 消息。
 *
 * <p>协议方法与 stdio 完全一致；只是把 stdin/stdout 换成 WebSocket 帧。
 */
public final class WsAcpConnection implements AcpConnection {

    private final AcpAgentProfile profile;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong nextId = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final StringBuilder textBuffer = new StringBuilder();

    private volatile WebSocket webSocket;
    private volatile Consumer<JsonNode> notificationHandler = ignored -> {
    };
    private volatile Consumer<JsonNode> requestHandler = node -> {
    };

    public WsAcpConnection(AcpAgentProfile profile) {
        this.profile = profile;
    }

    @Override
    public void start(Path cwd) {
        try {
            WebSocket.Builder builder = HttpClient.newHttpClient()
                    .newWebSocketBuilder();
            if (profile.authToken() != null && !profile.authToken().isBlank()) {
                builder.header("Authorization", "Bearer " + profile.authToken());
            }
            webSocket = builder.buildAsync(URI.create(profile.wsUrl()), new Listener())
                    .get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("连接 ACP WS 被中断: " + profile.wsUrl(), e);
        } catch (ExecutionException | TimeoutException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("连接 ACP WS 失败: " + profile.wsUrl()
                    + ": " + cause.getMessage(), cause);
        }
    }

    @Override
    public boolean isAlive() {
        WebSocket socket = webSocket;
        return socket != null && !socket.isOutputClosed() && !closed.get();
    }

    @Override
    public void onNotification(Consumer<JsonNode> handler) {
        this.notificationHandler = handler;
    }

    @Override
    public void onRequest(Consumer<JsonNode> handler) {
        this.requestHandler = handler;
    }

    @Override
    public JsonNode request(String method, Object params, long timeoutMs) {
        long id = nextId.incrementAndGet();
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.set("params", objectMapper.valueToTree(params));

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        send(request);
        try {
            JsonNode response = future.get(Math.max(1000, timeoutMs), TimeUnit.MILLISECONDS);
            JsonNode error = response.get("error");
            if (error != null && !error.isNull()) {
                throw new AcpException(
                        error.path("code").asInt(-32603),
                        error.path("message").asText("ACP request failed"));
            }
            JsonNode result = response.get("result");
            return result == null ? objectMapper.nullNode() : result;
        } catch (TimeoutException e) {
            throw new IllegalStateException("ACP WS 请求超时(" + timeoutMs + "ms): " + method, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ACP WS 请求被中断: " + method, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof AcpException acp) {
                throw acp;
            }
            throw new IllegalStateException("ACP WS 请求失败: " + method + ": " + cause.getMessage(), cause);
        } finally {
            pending.remove(id);
        }
    }

    @Override
    public void respond(Object id, Object result) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", objectMapper.valueToTree(id));
        response.set("result", objectMapper.valueToTree(result));
        send(response);
    }

    @Override
    public void respondError(Object id, int code, String message) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", objectMapper.valueToTree(id));
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        send(response);
    }

    private synchronized void send(JsonNode node) {
        WebSocket socket = webSocket;
        if (socket == null || closed.get()) {
            throw new IllegalStateException("ACP WS 连接尚未建立或已关闭");
        }
        try {
            String json = objectMapper.writeValueAsString(node);
            socket.sendText(json, true)
                    .get(10, TimeUnit.SECONDS);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("序列化 ACP WS 消息失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("发送 ACP WS 消息被中断", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("发送 ACP WS 消息失败: " + e.getMessage(), e);
        }
    }

    private void onMessage(String text) {
        JsonNode node;
        try {
            node = objectMapper.readTree(text);
        } catch (Exception e) {
            System.out.println("[AcpWs] 非 JSON 消息: " + text);
            return;
        }
        if (node.has("id") && (node.has("result") || node.has("error"))) {
            long id = node.path("id").asLong();
            CompletableFuture<JsonNode> future = pending.get(id);
            if (future != null) {
                future.complete(node);
            }
        } else if (node.has("method")) {
            if (node.has("id")) {
                requestHandler.accept(node);
            } else {
                notificationHandler.accept(node);
            }
        }
    }

    private void failAll(Throwable error) {
        pending.forEach((id, future) -> future.completeExceptionally(error));
        pending.clear();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        failAll(new IllegalStateException("ACP WS 连接已关闭"));
        WebSocket socket = webSocket;
        if (socket != null) {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                socket.abort();
            }
        }
    }

    private final class Listener implements WebSocket.Listener {

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String message = textBuffer.toString();
                textBuffer.setLength(0);
                onMessage(message);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            failAll(error);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            failAll(new IllegalStateException("ACP WS 关闭: " + statusCode + " " + reason));
            return null;
        }
    }
}
