package com.agent.demo.clientagent.client_tool;

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

    private static final String STATUS_ONLINE = "ONLINE";
    private static final String STATUS_OFFLINE = "OFFLINE";

    /** clientId -> 客户端注册信息 */
    private final ConcurrentMap<String, ClientRegistration> clients = new ConcurrentHashMap<>();

    private final RestClient restClient = RestClient.create();

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
            Map<String, String> parameters = definition.parameters() == null
                    ? Map.of() : Map.copyOf(definition.parameters());
            String callbackPath = definition.callbackPath();
            if (callbackPath != null && callbackPath.isBlank()) {
                callbackPath = null;
            }
            normalized.add(new ToolDefinition(
                    name,
                    definition.description() == null ? "" : definition.description(),
                    parameters,
                    callbackPath));
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

    /**
     * 管理视角的全量工具列表
     */
    public List<ToolDefinition> listAllTools() {
        return clients.values().stream()
                .flatMap(client -> client.tools().stream())
                .toList();
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
    public String execute(String clientId,
                          String endUserId,
                          String sessionId,
                          String toolName,
                          Map<String, Object> arguments) {
        String cid = requireClientId(clientId);
        ClientRegistration client = getClient(cid)
                .orElseThrow(() -> new IllegalArgumentException("客户端未注册: " + cid));

        return switch (client.clientType()) {
            case SERVER -> executeViaHttp(client, toolName, arguments);
            case MOBILE -> executeViaWebSocket(cid, endUserId, sessionId, toolName, arguments);
        };
    }

    private String executeViaHttp(ClientRegistration client,
                                  String toolName,
                                  Map<String, Object> arguments) {
        ToolDefinition tool = getTool(client.clientId(), toolName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "客户端 " + client.clientId() + " 未注册工具: " + toolName));
        String callbackUrl = client.resolveCallbackUrl(tool);
        Map<String, Object> args = arguments == null ? Map.of() : arguments;

        System.out.println("[ClientRegistry] 回调客户端 " + client.clientId()
                + " 执行 " + toolName + "，地址: " + callbackUrl + "，参数: " + args);
        try {
            String result = restClient.post()
                    .uri(callbackUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("clientId", client.clientId(), "tool", toolName, "arguments", args))
                    .retrieve()
                    .body(String.class);
            System.out.println("[ClientRegistry] 客户端 " + client.clientId() + " 返回结果: " + result);
            return result;
        } catch (RestClientException e) {
            throw new IllegalStateException("回调客户端工具失败(" + callbackUrl + "): " + e.getMessage(), e);
        }
    }

    /**
     * MOBILE 客户端工具执行：通过 WebSocket 推送到 session 绑定的连接。
     * 待 WsSessionManager 实现后接入，当前先抛出未实现异常。
     */
    private String executeViaWebSocket(String clientId,
                                       String endUserId,
                                       String sessionId,
                                       String toolName,
                                       Map<String, Object> arguments) {
        throw new UnsupportedOperationException(
                "MOBILE 客户端工具执行(WebSocket)尚未实现，clientId=" + clientId
                        + ", sessionId=" + sessionId + ", tool=" + toolName);
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
