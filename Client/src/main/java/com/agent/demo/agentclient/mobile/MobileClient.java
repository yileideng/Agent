package com.agent.demo.agentclient.mobile;

import com.agent.demo.agentclient.client.Client;
import com.agent.demo.agentclient.client.ClientTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * 移动端客户端模拟器：以 MOBILE 类型注册到中间件，并通过 WebSocket 长连接接收工具调用。
 *
 * <p>与 {@link Client}（SERVER 类型、HTTP 回调）的区别：
 * <ul>
 *   <li>手机 App 没有公网可达地址，无法提供 callbackUrl</li>
 *   <li>改为客户端主动出站连接中间件 {@code /ws/agent}，穿透 NAT</li>
 *   <li>中间件通过这条长连接下发工具调用，客户端本地执行后原连接回传结果</li>
 * </ul>
 *
 * <p>握手参数中的 {@code clientId + endUserId + deviceId} 决定路由身份，保证多用户互不串号。
 */
@Component
public class MobileClient {

    private final List<ClientTool> clientTools;
    private final Client client;
    private final String clientId;
    private final String endUserId;
    private final String deviceId;
    private final String wsUrl;
    private final String middlewareUrl;
    private final String clientPrompt;

    private final RestClient restClient = RestClient.create();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private volatile WebSocket webSocket;
    private volatile boolean registered;

    public MobileClient(List<ClientTool> clientTools,
                        Client client,
                        @Value("${agent-mobile.client-id:agent-mobile-client}") String clientId,
                        @Value("${agent-mobile.end-user-id:demo-user}") String endUserId,
                        @Value("${agent-mobile.device-id:demo-phone}") String deviceId,
                        @Value("${agent-mobile.ws-url:ws://localhost:8080/ws/agent}") String wsUrl,
                        @Value("${agent-client.middleware-url:http://localhost:8080}") String middlewareUrl,
                        @Value("${agent-mobile.client-prompt:移动端客户端定制化system prompt}") String clientPrompt) {
        this.clientTools = List.copyOf(clientTools);
        this.client = client;
        this.clientId = clientId;
        this.endUserId = endUserId;
        this.deviceId = deviceId;
        this.wsUrl = wsUrl;
        this.middlewareUrl = trimTrailingSlash(middlewareUrl);
        this.clientPrompt = clientPrompt;
    }

    /**
     * 启动即注册并建立 WebSocket；失败由定时任务重试，兼容中间件后启动的情况
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            ensureReady();
            System.out.println("[AgentMobile] 移动端客户端就绪: clientId=" + clientId
                    + "，endUserId=" + endUserId + "，deviceId=" + deviceId);
        } catch (Exception e) {
            System.out.println("[AgentMobile] 初始化失败，稍后重试: " + e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${agent-mobile.retry-ms:5000}")
    public void retry() {
        WebSocket ws = webSocket;
        if (registered && ws != null && !ws.isOutputClosed()) {
            return;
        }
        try {
            ensureReady();
        } catch (Exception ignored) {
            // 中间件未就绪时静默重试
        }
    }

    /**
     * 移动端对话入口：先确保 WS 已连接，再通过 HTTP 请求中间件 /chat。
     * Agent 在决策过程中需要的工具调用会经由 WS 推回本客户端执行。
     */
    public String chat(String prompt, String agent, String sessionId) {
        ensureReady();
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(middlewareUrl)
                .path("/chat")
                .queryParam("clientId", clientId)
                .queryParam("endUserId", endUserId)
                .queryParam("prompt", prompt);
        if (agent != null && !agent.isBlank()) {
            builder.queryParam("agent", agent);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            builder.queryParam("sessionId", sessionId);
        }
        if (clientPrompt != null && !clientPrompt.isBlank()) {
            builder.queryParam("clientPrompt", clientPrompt);
        }
        return restClient.get()
                .uri(builder.build().encode().toUri())
                .retrieve()
                .body(String.class);
    }

    public boolean isConnected() {
        WebSocket ws = webSocket;
        return ws != null && !ws.isOutputClosed();
    }

    // ── 注册与连接 ──

    private synchronized void ensureReady() {
        if (!registered) {
            register();
        }
        WebSocket ws = webSocket;
        if (ws == null || ws.isOutputClosed()) {
            connect();
        }
    }

    /**
     * 以 MOBILE 类型注册：不需要 baseCallbackUrl，工具调用走 WebSocket
     */
    private void register() {
        List<Map<String, Object>> tools = clientTools.stream()
                .map(tool -> Map.<String, Object>of(
                        "name", tool.getName(),
                        "description", tool.getDescription(),
                        "parameters", tool.getParameters()))
                .toList();
        String response = restClient.post()
                .uri(middlewareUrl + "/clients/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "clientId", clientId,
                        "clientType", "MOBILE",
                        "clientPrompt", clientPrompt == null ? "" : clientPrompt,
                        "tools", tools))
                .retrieve()
                .body(String.class);
        registered = true;
        System.out.println("[AgentMobile] 客户端 " + clientId + " 注册完成(MOBILE): " + response);
    }

    private void connect() {
        String uri = wsUrl + (wsUrl.contains("?") ? "&" : "?")
                + "clientId=" + encode(clientId)
                + "&endUserId=" + encode(endUserId)
                + "&deviceId=" + encode(deviceId);
        try {
            webSocket = httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(uri), new ToolListener())
                    .get(10, TimeUnit.SECONDS);
            System.out.println("[AgentMobile] WebSocket已连接: " + uri);
        } catch (Exception e) {
            webSocket = null;
            throw new IllegalStateException("连接中间件WebSocket失败(" + uri + "): " + e.getMessage(), e);
        }
    }

    // ── 消息处理 ──

    /**
     * 接收中间件推送的工具调用并回传执行结果
     */
    private final class ToolListener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String payload = buffer.toString();
                buffer.setLength(0);
                handleMessage(payload);
            }
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            System.out.println("[AgentMobile] WebSocket关闭: " + statusCode + " " + reason);
            // 中间件重启/断线后其内存中的客户端注册信息会丢失，重连前需重新注册
            registered = false;
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            System.out.println("[AgentMobile] WebSocket异常: " + error.getMessage());
            registered = false;
        }
    }

    private void handleMessage(String payload) {
        JsonNode node = jsonMapper.readTree(payload);
        String type = node.path("type").asText("");
        switch (type) {
            case "tool_call" -> handleToolCall(node);
            case "connected" -> System.out.println("[AgentMobile] 中间件确认连接，connectionId="
                    + node.path("connectionId").asText(""));
            case "pong" -> {
                // 心跳应答，无需处理
            }
            default -> System.out.println("[AgentMobile] 收到未知消息类型: " + type);
        }
    }

    private void handleToolCall(JsonNode node) {
        String callId = node.path("callId").asText("");
        String toolName = node.path("tool").asText("");
        Map<String, String> arguments = toArguments(node.get("arguments"));
        System.out.println("[AgentMobile] 收到工具调用: " + toolName
                + "，参数: " + arguments + "，callId=" + callId);

        ObjectNode message = jsonMapper.createObjectNode();
        message.put("type", "tool_result");
        message.put("callId", callId);
        try {
            Map<String, String> result = client.executeTool(toolName, arguments);
            ObjectNode resultNode = jsonMapper.createObjectNode();
            result.forEach(resultNode::put);
            message.set("result", resultNode);
            System.out.println("[AgentMobile] 工具执行结果: " + result);
        } catch (Exception e) {
            message.put("error", e.getMessage() == null ? "工具执行失败" : e.getMessage());
            System.out.println("[AgentMobile] 工具执行失败: " + e.getMessage());
        }
        send(message);
    }

    private void send(ObjectNode message) {
        WebSocket socket = webSocket;
        if (socket == null) {
            System.out.println("[AgentMobile] WebSocket未连接，无法回传工具结果");
            return;
        }
        socket.sendText(jsonMapper.writeValueAsString(message), true);
    }

    private Map<String, String> toArguments(JsonNode node) {
        Map<String, String> arguments = new HashMap<>();
        if (node != null && node.isObject()) {
            Map<?, ?> raw = jsonMapper.convertValue(node, Map.class);
            raw.forEach((key, value) ->
                    arguments.put(String.valueOf(key), value == null ? "" : String.valueOf(value)));
        }
        return arguments;
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String trimTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
