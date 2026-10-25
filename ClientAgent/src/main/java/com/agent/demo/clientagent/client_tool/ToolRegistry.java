package com.agent.demo.clientagent.client_tool;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 工具目录：统一存放所有客户端(含进程内的演示客户端)注册的工具。
 * 以 (clientId, name) 为唯一键，同名工具在不同客户端之间互不影响。
 * 工具由客户端自己执行，本服务只按 callbackUrl 转发调用。
 *
 * 说明：这里的 clientId 就是后续多租户隔离的边界，接入鉴权后由凭证解析得到，
 * 当前先由调用方显式传入（/mcp/{clientId} 或 X-Client-Id 请求头）。
 */
@Component
public class ToolRegistry {

    private final ConcurrentMap<String, ConcurrentMap<String, ToolDefinition>> toolsByClient = new ConcurrentHashMap<>();
    private final RestClient restClient = RestClient.create();

    /**
     * 注册一批工具，同一客户端重复注册会覆盖同名工具的旧定义
     */
    public List<ToolDefinition> register(String clientId, String callbackUrl, List<ToolDefinition> definitions) {
        String cid = requireClientId(clientId);
        validateCallbackUrl(callbackUrl);
        if (definitions == null || definitions.isEmpty()) {
            throw new IllegalArgumentException("tools不能为空");
        }
        ConcurrentMap<String, ToolDefinition> clientTools =
                toolsByClient.computeIfAbsent(cid, ignored -> new ConcurrentHashMap<>());
        List<ToolDefinition> normalized = new ArrayList<>();
        for (ToolDefinition definition : definitions) {
            String name = requireToolName(definition.name());
            Map<String, String> parameters = definition.parameters() == null
                    ? Map.of() : Map.copyOf(definition.parameters());
            ToolDefinition tool = new ToolDefinition(
                    cid, name, definition.description() == null ? "" : definition.description(),
                    parameters, callbackUrl);
            clientTools.put(name, tool);
            normalized.add(tool);
        }
        System.out.println("[ToolRegistry] 客户端 " + cid + " 注册工具: "
                + normalized.stream().map(ToolDefinition::name).toList());
        return List.copyOf(normalized);
    }

    public List<ToolDefinition> list(String clientId) {
        Map<String, ToolDefinition> clientTools = toolsByClient.get(requireClientId(clientId));
        return clientTools == null ? List.of() : List.copyOf(clientTools.values());
    }

    /**
     * 管理视角的全量工具列表
     */
    public List<ToolDefinition> listAll() {
        return toolsByClient.values().stream()
                .flatMap(clientTools -> clientTools.values().stream())
                .toList();
    }

    public Optional<ToolDefinition> get(String clientId, String name) {
        Map<String, ToolDefinition> clientTools = toolsByClient.get(requireClientId(clientId));
        return clientTools == null ? Optional.empty() : Optional.ofNullable(clientTools.get(name));
    }

    /**
     * @return 是否确实删除了该工具
     */
    public boolean unregister(String clientId, String name) {
        Map<String, ToolDefinition> clientTools = toolsByClient.get(requireClientId(clientId));
        return clientTools != null && clientTools.remove(name) != null;
    }

    /**
     * 注销某个客户端的全部工具（客户端下线/租户回收时使用）
     *
     * @return 删除的工具数量
     */
    public int unregisterClient(String clientId) {
        Map<String, ToolDefinition> removed = toolsByClient.remove(requireClientId(clientId));
        return removed == null ? 0 : removed.size();
    }

    /**
     * 调用客户端执行工具：POST callbackUrl {clientId, tool, arguments}，响应体即工具结果
     */
    public String execute(String clientId, String name, Map<String, Object> arguments) {
        String cid = requireClientId(clientId);
        ToolDefinition tool = get(cid, name)
                .orElseThrow(() -> new IllegalArgumentException("客户端 " + cid + " 未注册工具: " + name));
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        System.out.println("[ToolRegistry] 回调客户端 " + cid
                + " 执行 " + name + "，参数: " + args);
        try {
            String result = restClient.post()
                    .uri(tool.callbackUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("clientId", cid, "tool", name, "arguments", args))
                    .retrieve()
                    .body(String.class);
            System.out.println("[ToolRegistry] 客户端 " + cid + " 返回结果: " + result);
            return result;
        } catch (RestClientException e) {
            throw new IllegalStateException("回调客户端工具失败(" + tool.callbackUrl() + "): " + e.getMessage(), e);
        }
    }

    private String requireClientId(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("clientId不能为空");
        }
        return clientId.trim();
    }

    private String requireToolName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("工具name不能为空");
        }
        return name.trim();
    }

    private void validateCallbackUrl(String callbackUrl) {
        if (callbackUrl == null || callbackUrl.isBlank()) {
            throw new IllegalArgumentException("callbackUrl不能为空");
        }
        URI uri;
        try {
            uri = URI.create(callbackUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("callbackUrl格式非法: " + callbackUrl);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("callbackUrl必须是http/https地址: " + callbackUrl);
        }
    }
}
