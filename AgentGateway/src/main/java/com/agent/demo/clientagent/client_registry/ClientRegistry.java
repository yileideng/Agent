package com.agent.demo.clientagent.client_registry;

import com.agent.demo.clientagent.core.ToolCallContext;
import com.agent.demo.clientagent.core.conversation.ConversationKey;
import com.agent.demo.clientagent.core.conversation.ConversationStore;
import com.agent.demo.clientagent.ws.WsSessionManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 客户端注册表：把"客户端"提升为一级注册实体，统一管理客户端元数据与工具列表。
 *
 * <p>职责：
 * <ul>
 *   <li>客户端注册/查询/注销，工具按 {@code (clientId, name)} 隔离</li>
 *   <li>根据 {@link ClientType} 决定工具调用走 HTTP 回调还是 WebSocket 推送</li>
 *   <li>回调地址解析：{@code baseCallbackUrl + (tool.callbackPath ?: defaultCallbackPath)}</li>
 * </ul>
 *
 * <p>说明：{@code clientId} 是后续多租户隔离的边界，接入鉴权后由凭证解析得到，
 * 当前先由调用方显式传入。
 */
@Component
public class ClientRegistry {

    /** 客户端注册后的状态；当前没有离线检测，注册即 ONLINE。 */
    private static final String STATUS_ONLINE = "ONLINE";
    /** 工具事件在共享会话中的来源标签。 */
    private static final String TOOL_AGENT_LABEL = "client-tools";

    /** clientId -> 客户端注册信息 */
    private final ConcurrentMap<String, ClientRegistration> clients = new ConcurrentHashMap<>();

    /** 调用 SERVER 客户端 HTTP 回调。 */
    private final RestClient restClient = RestClient.create();
    /** 构造 tool_call/tool_result 信封。 */
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** MOBILE 客户端的 WS 推送/等待结果。 */
    private final WsSessionManager wsSessionManager;
    /** 共享会话历史：记录真实工具执行，供切换 Agent 时交接。 */
    private final ConversationStore conversations;
    /** 等待客户端工具结果的默认超时（毫秒）。 */
    private final long toolTimeoutMs;

    public ClientRegistry(WsSessionManager wsSessionManager,
                          ConversationStore conversations,
                          @Value("${agent.tool.timeout-ms:60000}") long toolTimeoutMs) {
        this.wsSessionManager = wsSessionManager;
        this.conversations = conversations;
        this.toolTimeoutMs = Math.max(1000, toolTimeoutMs);
    }

    /**
     * 为一次工具调用创建上下文：chatId 标识本轮，callId 标识本次调用。
     */
    public ToolCallContext newContext(String clientId,
                                      String endUserId,
                                      String sessionId,
                                      String chatId) {
        return new ToolCallContext(clientId, endUserId, sessionId, chatId,
                UUID.randomUUID().toString(), toolTimeoutMs);
    }

    // ── 客户端注册 ──

    /**
     * 注册客户端（含工具列表），同一 clientId 重复注册会覆盖旧的注册信息。
     *
     * @param clientId            客户端唯一标识
     * @param clientType          客户端类型：SERVER / MOBILE
     * @param baseCallbackUrl     回调基地址（SERVER 必填，MOBILE 为空）
     * @param defaultCallbackPath 默认回调路径（可选）
     * @param clientPrompt        客户端定制系统提示词（可选）
     * @param definitions         工具列表
     * @return 注册后的客户端信息
     */
    public ClientRegistration register(String clientId,
                                       ClientType clientType,
                                       String baseCallbackUrl,
                                       String defaultCallbackPath,
                                       String clientPrompt,
                                       List<ToolDefinition> definitions) {
        String cid = requireClientId(clientId);
        ClientType type = requireClientType(clientType);
        validateBaseCallbackUrl(type, baseCallbackUrl);
        if (definitions == null || definitions.isEmpty()) {
            throw new IllegalArgumentException("tools不能为空");
        }

        List<ToolDefinition> normalized = new ArrayList<>();
        for (ToolDefinition definition : definitions) {
            String name = requireToolName(definition.name());
            String callbackPath = definition.callbackPath();
            if (callbackPath != null && callbackPath.isBlank()) {
                callbackPath = null;
            }
            Map<String, Object> inputSchema = definition.inputSchema() == null
                    ? Map.of("type", "object", "additionalProperties", false)
                    : Map.copyOf(definition.inputSchema());
            normalized.add(new ToolDefinition(
                    name,
                    definition.description() == null ? "" : definition.description(),
                    callbackPath,
                    inputSchema));
        }

        ClientRegistration registration = new ClientRegistration(
                cid, type, baseCallbackUrl, defaultCallbackPath, clientPrompt,
                List.copyOf(normalized), Instant.now(), STATUS_ONLINE);
        clients.put(cid, registration);

        System.out.println("[ClientRegistry] 客户端 " + cid + "(" + type + ") 注册工具: "
                + normalized.stream().map(ToolDefinition::name).toList());
        return registration;
    }

    // ── 客户端查询 ──

    public Optional<ClientRegistration> getClient(String clientId) {
        return Optional.ofNullable(clients.get(requireClientId(clientId)));
    }

    public List<ClientRegistration> listClients() {
        return List.copyOf(clients.values());
    }

    // ── 工具查询（按客户端隔离）──

    public List<ToolDefinition> listTools(String clientId) {
        ClientRegistration client = clients.get(requireClientId(clientId));
        return client == null ? List.of() : client.tools();
    }

    public Optional<ToolDefinition> getTool(String clientId, String name) {
        ClientRegistration client = clients.get(requireClientId(clientId));
        if (client == null) {
            return Optional.empty();
        }
        return client.tools().stream()
                .filter(tool -> tool.name().equals(name))
                .findFirst();
    }

    // ── 注销 ──

    /**
     * @return 是否确实删除了该工具
     */
    public boolean unregisterTool(String clientId, String name) {
        String cid = requireClientId(clientId);
        ClientRegistration client = clients.get(cid);
        if (client == null) {
            return false;
        }
        boolean removed = client.tools().stream().anyMatch(tool -> tool.name().equals(name));
        if (removed) {
            List<ToolDefinition> remaining = client.tools().stream()
                    .filter(tool -> !tool.name().equals(name))
                    .toList();
            clients.put(cid, new ClientRegistration(
                    client.clientId(), client.clientType(), client.baseCallbackUrl(),
                    client.defaultCallbackPath(), client.clientPrompt(), remaining,
                    client.registeredAt(), client.status()));
        }
        return removed;
    }

    /**
     * 注销某个客户端（客户端下线/租户回收时使用）
     *
     * @return 删除的工具数量
     */
    public int unregisterClient(String clientId) {
        ClientRegistration removed = clients.remove(requireClientId(clientId));
        return removed == null ? 0 : removed.tools().size();
    }

    // ── 工具执行 ──

    /**
     * 执行客户端工具：根据客户端类型选择传输方式。
     * <ul>
     *   <li>SERVER：HTTP POST 到解析后的回调地址</li>
     *   <li>MOBILE：通过 WebSocket 推送到 session 绑定的连接（待实现）</li>
     * </ul>
     *
     * @param clientId  客户端标识
     * @param endUserId 终端用户标识（MOBILE 路由时使用，SERVER 可空）
     * @param sessionId 会话标识（MOBILE 路由时用于定位 WS 连接，SERVER 可空）
     * @param toolName  工具名
     * @param arguments 工具参数
     * @return 工具执行结果
     */
    public String execute(ToolCallContext context,
                          String toolName,
                          Map<String, Object> arguments) {
        String cid = requireClientId(context.clientId());
        ClientRegistration client = getClient(cid)
                .orElseThrow(() -> new IllegalArgumentException("客户端未注册: " + cid));

        ConversationKey key = conversationKey(context);
        String callId = context.callId();
        if (key != null) {
            // 只记录真实发生的调用；模型"打算调用"不等于已经执行。
            conversations.appendToolCall(key, context.chatId(), agentLabel(key), toolName, callId, arguments);
        }
        try {
            String result = switch (client.clientType()) {
                case SERVER -> executeViaHttp(context, client, toolName, arguments);
                case MOBILE -> executeViaWebSocket(context, toolName, arguments);
            };
            if (key != null) {
                conversations.appendToolResult(key, context.chatId(), agentLabel(key), toolName, callId, true, result);
            }
            return result;
        } catch (RuntimeException e) {
            if (key != null) {
                conversations.appendToolResult(key, context.chatId(), agentLabel(key), toolName, callId,
                        false, e.getMessage());
            }
            throw e;
        }
    }

    /**
     * 工具事件归属的共享会话；没有会话 ID 的历史调用不落库。
     */
    private ConversationKey conversationKey(ToolCallContext context) {
        String sessionId = context.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return new ConversationKey(
                context.clientId() == null || context.clientId().isBlank() ? "default" : context.clientId(),
                context.endUserId() == null || context.endUserId().isBlank() ? "default" : context.endUserId(),
                sessionId);
    }

    /**
     * 工具事件的操作方名称。
     *
     * <p>工具回调线程拿不到"哪个 Agent 发起的"这一信息（MCP 请求里没有该字段），
     * 因此统一标注为 {@code client-tools}，避免把工具结果错误归到某个模型名下。
     */
    private String agentLabel(ConversationKey key) {
        return TOOL_AGENT_LABEL;
    }

    /**
     * SERVER 客户端：把 tool_call 信封 POST 到 baseCallbackUrl + callbackPath，
     * 读取响应里的 tool_result 信封。
     */
    private String executeViaHttp(ToolCallContext context,
                                  ClientRegistration client,
                                  String toolName,
                                  Map<String, Object> arguments) {
        ToolDefinition tool = getTool(client.clientId(), toolName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "客户端 " + client.clientId() + " 未注册工具: " + toolName));
        String callbackUrl = client.resolveCallbackUrl(tool);
        Map<String, Object> args = arguments == null ? Map.of() : arguments;

        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("v", "0.1");
        envelope.put("id", context.callId());
        envelope.put("type", "tool_call");
        ObjectNode payload = envelope.putObject("payload");
        payload.put("clientId", client.clientId());
        payload.put("endUserId", context.endUserId());
        payload.put("sessionId", context.sessionId());
        payload.put("chatId", context.chatId());
        payload.put("callId", context.callId());
        payload.put("tool", toolName);
        payload.set("arguments", objectMapper.valueToTree(args));
        payload.put("deadlineMs", context.deadlineMs());

        System.out.println("[ClientRegistry] 回调客户端 " + client.clientId()
                + " 执行 " + toolName + "，callId=" + context.callId()
                + "，chatId=" + context.chatId() + "，地址: " + callbackUrl);
        try {
            String body = objectMapper.writeValueAsString(envelope);
            String response = restClient.post()
                    .uri(callbackUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            System.out.println("[ClientRegistry] 客户端 " + client.clientId() + " 返回结果: " + response);
            return extractToolResult(response);
        } catch (RestClientException e) {
            throw new IllegalStateException("回调客户端工具失败(" + callbackUrl + "): " + e.getMessage(), e);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化工具调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析客户端的 tool_result 信封。
     */
    private String extractToolResult(String response) {
        try {
            JsonNode node = objectMapper.readTree(response);
            if (!"tool_result".equals(node.path("type").asText())) {
                throw new IllegalStateException("客户端返回了非 tool_result 响应: " + response);
            }
            JsonNode payload = node.path("payload");
            if ("error".equals(payload.path("status").asText())) {
                throw new IllegalStateException(payload.path("error").path("message")
                        .asText("工具执行失败"));
            }
            JsonNode result = payload.get("result");
            return result == null ? "" : (result.isTextual() ? result.asText() : result.toString());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("解析客户端工具结果失败: " + e.getMessage(), e);
        }
    }

    /**
     * MOBILE 客户端工具执行：通过 WebSocket 推送到 session 绑定的连接，并等待客户端回传结果。
     * 路由按 (clientId, endUserId, sessionId) 隔离，保证不同终端用户不会串号。
     */
    private String executeViaWebSocket(ToolCallContext context,
                                       String toolName,
                                       Map<String, Object> arguments) {
        getTool(context.clientId(), toolName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "客户端 " + context.clientId() + " 未注册工具: " + toolName));
        return wsSessionManager.invoke(context, toolName, arguments);
    }

    // ── 校验工具 ──

    private String requireClientId(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("clientId不能为空");
        }
        return clientId.trim();
    }

    private ClientType requireClientType(ClientType clientType) {
        if (clientType == null) {
            throw new IllegalArgumentException("clientType不能为空");
        }
        return clientType;
    }

    private String requireToolName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("工具name不能为空");
        }
        return name.trim();
    }

    private void validateBaseCallbackUrl(ClientType clientType, String baseCallbackUrl) {
        if (clientType == ClientType.MOBILE) {
            // MOBILE 走 WS，不需要回调地址
            return;
        }
        if (baseCallbackUrl == null || baseCallbackUrl.isBlank()) {
            throw new IllegalArgumentException("SERVER 客户端的 baseCallbackUrl 不能为空");
        }
        URI uri;
        try {
            uri = URI.create(baseCallbackUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("baseCallbackUrl 格式非法: " + baseCallbackUrl);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("baseCallbackUrl 必须是 http/https 地址: " + baseCallbackUrl);
        }
    }
}
