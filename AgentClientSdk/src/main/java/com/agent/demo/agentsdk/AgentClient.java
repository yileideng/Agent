package com.agent.demo.agentsdk;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 客户端 SDK：把 WebSocket 的连接、注册、心跳、重连、并发执行和 callId 关联全部封装起来。
 *
 * <p>客户端开发者只做两件事：
 * <ol>
 *   <li>用 {@link Builder#tool(String, String, Map, ToolHandler)} 声明本地工具；</li>
 *   <li>调用 {@link #connect()}。</li>
 * </ol>
 *
 * <p>示例：
 * <pre>{@code
 * AgentClient client = AgentClient.builder()
 *         .gateway("http://localhost:8080")
 *         .clientId("xiaohongshu")
 *         .endUserId("u-123")
 *         .tool("get_user_profile", "获取当前用户资料",
 *               Map.of("type", "object", "additionalProperties", false),
 *               args -> Map.of("name", "张三"))
 *         .build();
 * client.connect();
 * }</pre>
 *
 * <p>两种传输共用同一套 API：
 * <ul>
 *   <li>{@link Transport#WEBSOCKET}：客户端主动出站建连，适合 PC/App/NAT 客户端；</li>
 *   <li>{@link Transport#HTTP_CALLBACK}：客户端暴露回调端点，适合服务端客户端。</li>
 * </ul>
 * 工具定义、ToolHandler、注册、chat、tool_result 生成完全一致；
 * 差别只在底层通道，以及 HTTP 模式需要客户端提供一个三行的回调 Controller。
 */
public final class AgentClient implements AutoCloseable {

    /** 网关 HTTP 地址，注册/对话都请求它。 */
    private final String gatewayUrl;
    /** WebSocket 地址，WEBSOCKET 模式下使用。 */
    private final String wsUrl;
    /** 客户端唯一标识。 */
    private final String clientId;
    /** 终端用户标识，网关按它做用户级隔离。 */
    private final String endUserId;
    /** 注入给 Agent 的客户端系统提示词。 */
    private final String clientPrompt;
    /** 已声明的工具：name -> ToolSpec。 */
    private final Map<String, ToolSpec> tools;
    /** 工具调用通道：HTTP_CALLBACK 或 WEBSOCKET。 */
    private final Transport transport;
    /** HTTP_CALLBACK 模式：客户端可被网关访问的基地址。 */
    private final String callbackBaseUrl;
    /** HTTP_CALLBACK 模式：默认回调路径。 */
    private final String callbackPath;
    /** 对话请求超时秒数，必须大于网关的工具等待时间。 */
    private final long chatTimeoutSeconds;

    /** JSON 编解码。 */
    private final JsonMapper json = JsonMapper.builder().build();
    /** 注册、对话和 WebSocket 建连共用的 HTTP 客户端。 */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    /** 工具执行线程池，允许多个 tool_call 并发执行。 */
    private final ExecutorService toolExecutor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "agent-sdk-tool");
        thread.setDaemon(true);
        return thread;
    });
    /** 心跳与断线重连调度器。 */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "agent-sdk-bg");
        thread.setDaemon(true);
        return thread;
    });

    /** 当前 WebSocket 连接；HTTP 模式为 null。 */
    private final AtomicReference<WebSocket> socket = new AtomicReference<>();
    /** 后台任务是否已启动，避免重复调度。 */
    private final AtomicBoolean started = new AtomicBoolean(false);
    /** 是否已关闭。 */
    private final AtomicBoolean closed = new AtomicBoolean(false);
    /** 串行化 WebSocket 发送，避免多线程同时写帧。 */
    private final Object sendLock = new Object();

    /** 工具是否已注册成功；断线后会重置，重连时重新注册。 */
    private volatile boolean registered;

    private AgentClient(String gatewayUrl,
                        String wsUrl,
                        String clientId,
                        String endUserId,
                        String clientPrompt,
                        Map<String, ToolSpec> tools,
                        Transport transport,
                        String callbackBaseUrl,
                        String callbackPath,
                        long chatTimeoutSeconds) {
        this.gatewayUrl = gatewayUrl;
        this.wsUrl = wsUrl;
        this.clientId = clientId;
        this.endUserId = endUserId;
        this.clientPrompt = clientPrompt == null ? "" : clientPrompt;
        this.tools = tools;
        this.transport = transport;
        this.callbackBaseUrl = callbackBaseUrl;
        this.callbackPath = callbackPath;
        this.chatTimeoutSeconds = Math.max(10, chatTimeoutSeconds);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 注册工具并建立 WebSocket；后台会维持心跳和断线重连。
     */
    public void connect() {
        if (closed.get()) {
            throw new IllegalStateException("AgentClient 已关闭");
        }
        // 先启动后台重试，保证首次注册/连接失败时也能自动恢复
        startBackgroundTasks();
        ensureRegistered();
        if (transport == Transport.WEBSOCKET) {
            connectWebSocket();
        }
    }

    /**
     * 向中间件发起一轮对话，返回中间件原始 JSON 响应。
     */
    public String chat(String prompt, String agent, String sessionId) {
        ObjectNode request = envelope("chat");
        ObjectNode payload = (ObjectNode) request.get("payload");
        payload.put("clientId", clientId);
        payload.put("endUserId", endUserId);
        payload.put("prompt", prompt);
        if (agent != null && !agent.isBlank()) {
            payload.put("agent", agent);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            payload.put("sessionId", sessionId);
        }
        if (clientPrompt != null && !clientPrompt.isBlank()) {
            payload.put("clientPrompt", clientPrompt);
        }
        HttpResponse<String> response = postJson(gatewayUrl + "/chat", request,
                Duration.ofSeconds(chatTimeoutSeconds));
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("对话失败: HTTP " + response.statusCode()
                    + " " + response.body());
        }
        return response.body();
    }

    /**
     * WS 模式返回连接是否打开；HTTP 回调模式返回工具是否已注册成功。
     */
    public boolean isConnected() {
        if (transport == Transport.HTTP_CALLBACK) {
            // HTTP 回调没有长连接，注册成功即视为就绪
            return registered;
        }
        WebSocket ws = socket.get();
        return ws != null && !ws.isOutputClosed();
    }

    /**
     * 停止心跳/重连、关闭工具线程池；WS 模式下再关闭连接。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        scheduler.shutdownNow();
        toolExecutor.shutdownNow();
        if (transport == Transport.WEBSOCKET) {
            WebSocket ws = socket.getAndSet(null);
            if (ws != null) {
                try {
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "client close")
                            .get(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // 关闭失败不影响退出
                }
            }
        }
    }

    // ── 注册 ──

    private synchronized void ensureRegistered() {
        if (registered) {
            return;
        }
        ObjectNode request = envelope("register_tools");
        ObjectNode body = (ObjectNode) request.get("payload");
        body.put("clientId", clientId);
        body.put("clientType", transport == Transport.HTTP_CALLBACK ? "SERVER" : "MOBILE");
        body.put("clientPrompt", clientPrompt);
        if (transport == Transport.HTTP_CALLBACK) {
            body.put("baseCallbackUrl", callbackBaseUrl);
            body.put("defaultCallbackPath", callbackPath);
        }
        ArrayNode toolNodes = body.putArray("tools");
        for (ToolSpec tool : tools.values()) {
            ObjectNode node = toolNodes.addObject();
            node.put("name", tool.name());
            node.put("description", tool.description());
            node.set("inputSchema", json.valueToTree(tool.inputSchema()));
        }

        HttpResponse<String> response = postJson(gatewayUrl + "/clients/register", request);
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("注册工具失败: HTTP " + response.statusCode()
                    + " " + response.body());
        }
        registered = true;
        System.out.println("[AgentSdk] 工具注册完成: " + tools.keySet());
    }

    private HttpResponse<String> postJson(String url, ObjectNode body) {
        return postJson(url, body, Duration.ofSeconds(10));
    }

    private HttpResponse<String> postJson(String url, ObjectNode body, Duration timeout) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            json.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("请求失败(" + url + "): " + e.getMessage(), e);
        }
    }

    // ── WebSocket ──

    private synchronized void connectWebSocket() {
        WebSocket current = socket.get();
        if (current != null && !current.isOutputClosed()) {
            return;
        }
        String uri = wsUrl;
        try {
            WebSocket ws = http.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(uri), new Listener())
                    .get(10, TimeUnit.SECONDS);
            socket.set(ws);
            System.out.println("[AgentSdk] WebSocket 已连接: " + uri);

            // CAP 握手：连上后先发 hello，网关回 hello_ack 并绑定 (clientId, endUserId)
            ObjectNode hello = envelope("hello");
            ObjectNode helloPayload = (ObjectNode) hello.get("payload");
            helloPayload.put("clientId", clientId);
            helloPayload.put("endUserId", endUserId);
            send(ws, hello);
        } catch (Exception e) {
            throw new IllegalStateException("连接 WebSocket 失败(" + uri + "): "
                    + e.getMessage(), e);
        }
    }

    private void startBackgroundTasks() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        if (transport == Transport.WEBSOCKET) {
            scheduler.scheduleWithFixedDelay(this::heartbeat, 30, 30, TimeUnit.SECONDS);
            scheduler.scheduleWithFixedDelay(this::reconnectIfNeeded, 5, 5, TimeUnit.SECONDS);
        } else {
            // HTTP 回调没有长连接，只需要保证注册成功
            scheduler.scheduleWithFixedDelay(this::ensureRegisteredSafe, 5, 5, TimeUnit.SECONDS);
        }
    }

    private void ensureRegisteredSafe() {
        if (closed.get()) {
            return;
        }
        try {
            ensureRegistered();
        } catch (Exception e) {
            System.out.println("[AgentSdk] 注册重试失败: " + e.getMessage());
        }
    }

    private void heartbeat() {
        if (transport != Transport.WEBSOCKET) {
            return;
        }
        WebSocket ws = socket.get();
        if (ws == null || ws.isOutputClosed()) {
            registered = false;
            return;
        }
        try {
            send(ws, envelope("ping"));
        } catch (Exception e) {
            registered = false;
        }
    }

    private void reconnectIfNeeded() {
        if (closed.get() || transport != Transport.WEBSOCKET) {
            return;
        }
        WebSocket ws = socket.get();
        if (ws != null && !ws.isOutputClosed()) {
            return;
        }
        try {
            ensureRegistered();
            connectWebSocket();
        } catch (Exception e) {
            System.out.println("[AgentSdk] 重连失败，稍后重试: " + e.getMessage());
        }
    }

    private void handleMessage(String payload) {
        JsonNode node;
        try {
            node = json.readTree(payload);
        } catch (Exception e) {
            System.out.println("[AgentSdk] 无法解析消息: " + payload);
            return;
        }
        String type = node.path("type").asText("");
        JsonNode body = node.path("payload");
        switch (type) {
            case "hello_ack" -> System.out.println("[AgentSdk] hello_ack: "
                    + body.path("connectionId").asText(""));
            case "tool_call" -> toolExecutor.submit(() -> handleWsToolCall(node, body));
            case "pong" -> {
                // 心跳应答
            }
            case "error" -> System.out.println("[AgentSdk] 网关错误: " + body);
            default -> System.out.println("[AgentSdk] 未知消息类型: " + type);
        }
    }

    /**
     * HTTP 回调入口：解析 tool_call 信封，执行工具，返回 tool_result 信封 JSON。
     * 与 WebSocket 路径共用同一套解析/执行逻辑。
     */
    public String handleToolCall(String payload) {
        JsonNode root;
        try {
            root = json.readTree(payload);
        } catch (Exception e) {
            return json.writeValueAsString(errorEnvelope(
                    "INVALID_REQUEST", "无法解析 tool_call: " + e.getMessage()));
        }
        if (!"tool_call".equals(root.path("type").asText()) || !root.has("payload")) {
            return json.writeValueAsString(errorEnvelope(
                    "INVALID_REQUEST", "请求体必须是 tool_call 信封"));
        }
        return json.writeValueAsString(executeToolCall(root, root.path("payload")));
    }

    private void handleWsToolCall(JsonNode requestRoot, JsonNode body) {
        ObjectNode message = executeToolCall(requestRoot, body);
        String callId = body.path("callId").asText("");
        WebSocket ws = socket.get();
        if (ws == null || ws.isOutputClosed()) {
            System.out.println("[AgentSdk] 连接已关闭，丢弃工具结果 callId=" + callId);
            return;
        }
        try {
            send(ws, message);
        } catch (Exception e) {
            System.out.println("[AgentSdk] 回传工具结果失败: " + e.getMessage());
        }
    }

    /**
     * 工具执行与 tool_result 构造：HTTP 和 WebSocket 共用。
     */
    private ObjectNode executeToolCall(JsonNode requestRoot, JsonNode body) {
        String callId = body.path("callId").asText("");
        String chatId = body.path("chatId").asText(null);
        String toolName = body.path("tool").asText("");
        Map<String, Object> arguments = toMap(body.get("arguments"));
        System.out.println("[AgentSdk] 收到工具调用 " + toolName
                + "，callId=" + callId + "，chatId=" + chatId + "，参数=" + arguments);

        ObjectNode message = envelope("tool_result");
        JsonNode requestId = requestRoot == null ? null : requestRoot.get("id");
        if (requestId != null && !requestId.isNull()) {
            message.set("id", requestId);
        }
        ObjectNode payload = (ObjectNode) message.get("payload");
        if (chatId != null && !chatId.isBlank()) {
            payload.put("chatId", chatId);
        }
        payload.put("callId", callId);
        ToolSpec tool = tools.get(toolName);
        try {
            if (tool == null) {
                throw new IllegalArgumentException("客户端未提供工具: " + toolName);
            }
            Map<String, Object> result = tool.handler().execute(arguments);
            payload.put("status", "success");
            payload.set("result", json.valueToTree(result == null ? Map.of() : result));
        } catch (Exception e) {
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            payload.put("status", "error");
            ObjectNode errorNode = payload.putObject("error");
            errorNode.put("code", "TOOL_FAILED");
            errorNode.put("message", error);
            System.out.println("[AgentSdk] 工具执行失败 " + toolName + ": " + error);
        }
        return message;
    }

    private void send(WebSocket ws, ObjectNode message) {
        String payload = json.writeValueAsString(message);
        synchronized (sendLock) {
            ws.sendText(payload, true).join();
        }
    }

    /**
     * 构造 CAP v0.1 信封：{v, id, type, payload}。
     */
    private ObjectNode envelope(String type) {
        ObjectNode node = json.createObjectNode();
        node.put("v", "0.1");
        node.put("id", UUID.randomUUID().toString());
        node.put("type", type);
        node.putObject("payload");
        return node;
    }

    private ObjectNode errorEnvelope(String code, String message) {
        ObjectNode node = envelope("error");
        ObjectNode payload = (ObjectNode) node.get("payload");
        payload.put("code", code);
        payload.put("message", message);
        return node;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        return json.convertValue(node, Map.class);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String trimTrailingSlash(String value) {
        return value != null && value.endsWith("/")
                ? value.substring(0, value.length() - 1) : value;
    }

    private static String deriveWsUrl(String gateway) {
        String ws;
        if (gateway.startsWith("https://")) {
            ws = "wss://" + gateway.substring("https://".length());
        } else if (gateway.startsWith("http://")) {
            ws = "ws://" + gateway.substring("http://".length());
        } else {
            ws = gateway;
        }
        return trimTrailingSlash(ws) + "/ws/agent";
    }

    /**
     * SDK 构建器：客户端开发者只在这里声明连接参数和工具。
     */
    public static final class Builder {

        private String gateway;
        private String wsUrl;
        private String clientId;
        private String endUserId;
        private String clientPrompt = "";
        private Transport transport = Transport.WEBSOCKET;
        private String callbackBaseUrl;
        private String callbackPath = "/client/tools/execute";
        private long chatTimeoutSeconds = 180;
        private final Map<String, ToolSpec> tools = new LinkedHashMap<>();

        /** 网关 HTTP 地址，例如 http://localhost:8080。 */
        public Builder gateway(String gateway) {
            this.gateway = gateway;
            return this;
        }

        /** 工具调用通道：HTTP_CALLBACK 或 WEBSOCKET。 */
        public Builder transport(Transport transport) {
            this.transport = transport == null ? Transport.WEBSOCKET : transport;
            return this;
        }

        /** HTTP_CALLBACK 模式必填：客户端可被网关访问的基地址。 */
        public Builder callbackBaseUrl(String callbackBaseUrl) {
            this.callbackBaseUrl = callbackBaseUrl;
            return this;
        }

        /** HTTP_CALLBACK 模式的默认回调路径，缺省 /client/tools/execute。 */
        public Builder callbackPath(String callbackPath) {
            this.callbackPath = callbackPath;
            return this;
        }

        /**
         * 对话请求的超时时间。必须大于网关等待客户端工具结果的超时（默认 60s），
         * 否则网关还在等工具回调时，SDK 就先超时了。
         */
        public Builder chatTimeoutSeconds(long chatTimeoutSeconds) {
            this.chatTimeoutSeconds = chatTimeoutSeconds;
            return this;
        }

        /** WEBSOCKET 模式地址；缺省从 gateway 推导为 ws(s)://host/ws/agent。 */
        public Builder wsUrl(String wsUrl) {
            this.wsUrl = wsUrl;
            return this;
        }

        /** 客户端唯一标识，对应网关的 clientId/租户边界。 */
        public Builder clientId(String clientId) {
            this.clientId = clientId;
            return this;
        }

        /** 终端用户标识，网关按它做用户级隔离。 */
        public Builder endUserId(String endUserId) {
            this.endUserId = endUserId;
            return this;
        }

        /** 注入给 Agent 的客户端系统提示词。 */
        public Builder clientPrompt(String clientPrompt) {
            this.clientPrompt = clientPrompt;
            return this;
        }

        /** 声明一个本地工具；inputSchema 是标准 JSON Schema。 */
        public Builder tool(String name,
                            String description,
                            Map<String, Object> inputSchema,
                            ToolHandler handler) {
            Map<String, Object> schema = (inputSchema == null || inputSchema.isEmpty())
                    ? Map.of("type", "object", "additionalProperties", false)
                    : Map.copyOf(inputSchema);
            tools.put(name, new ToolSpec(name, description, schema, handler));
            return this;
        }

        public AgentClient build() {
            if (gateway == null || gateway.isBlank()) {
                throw new IllegalArgumentException("gateway 不能为空");
            }
            if (clientId == null || clientId.isBlank()) {
                throw new IllegalArgumentException("clientId 不能为空");
            }
            if (endUserId == null || endUserId.isBlank()) {
                throw new IllegalArgumentException("endUserId 不能为空");
            }
            if (transport == Transport.HTTP_CALLBACK
                    && (callbackBaseUrl == null || callbackBaseUrl.isBlank())) {
                throw new IllegalArgumentException("HTTP_CALLBACK 模式必须设置 callbackBaseUrl");
            }
            String normalizedGateway = trimTrailingSlash(gateway);
            String normalizedWsUrl = (wsUrl == null || wsUrl.isBlank())
                    ? deriveWsUrl(normalizedGateway) : wsUrl;
            String normalizedCallback = callbackBaseUrl == null
                    ? null : trimTrailingSlash(callbackBaseUrl);
            return new AgentClient(normalizedGateway, normalizedWsUrl, clientId,
                    endUserId, clientPrompt, Map.copyOf(tools),
                    transport, normalizedCallback, callbackPath, chatTimeoutSeconds);
        }
    }

    /**
     * WebSocket 监听：把收到的文本帧解析成 SDK 内部消息。
     */
    private final class Listener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String payload = buffer.toString();
                buffer.setLength(0);
                handleMessage(payload);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            socket.compareAndSet(webSocket, null);
            registered = false;
            System.out.println("[AgentSdk] WebSocket 断开: " + statusCode + " " + reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            socket.compareAndSet(webSocket, null);
            registered = false;
            System.out.println("[AgentSdk] WebSocket 异常: " + error.getMessage());
        }
    }
}
