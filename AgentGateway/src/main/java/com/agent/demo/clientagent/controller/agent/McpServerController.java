package com.agent.demo.clientagent.controller.agent;

import com.agent.demo.clientagent.client_registry.ClientRegistry;
import com.agent.demo.clientagent.client_registry.ToolDefinition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP Server（Streamable HTTP 传输）：把注册的客户端工具暴露给支持 MCP 的 Agent
 * （如 Qwen Code / Claude Code）。
 *
 * <p>唯一入口是 {@code POST /mcp/{clientId}}：URL 中的 clientId 决定本次请求
 * 只能看到/调用该客户端的工具。
 */
@RestController
public class McpServerController {

    /**
     * JSON-RPC 2.0 请求。
     *
     * @param jsonrpc 固定 "2.0"
     * @param id      请求 ID，原样回显；为 null 表示通知
     * @param method  initialize / tools/list / tools/call / ping
     * @param params  method 相关参数；不同 method 结构不同，故保留为 Map
     */
    public record McpRequest(String jsonrpc, Object id, String method, Map<String, Object> params) {
    }

    /**
     * JSON-RPC 错误对象。
     *
     * @param code    JSON-RPC 错误码，如 -32601 Method not found
     * @param message 可读错误信息
     */
    public record McpError(int code, String message) {
    }

    /** 客户端未声明协议版本时使用的默认值。 */
    private static final String DEFAULT_PROTOCOL_VERSION = "2025-03-26";

    /** 客户端工具目录，tools/list 和 tools/call 都从这里取。 */
    private final ClientRegistry clientRegistry;

    public McpServerController(ClientRegistry clientRegistry) {
        this.clientRegistry = clientRegistry;
    }

    /**
     * POST /mcp/{clientId}
     *
     * <p>作用：MCP Streamable HTTP 端点，供支持 MCP 的 Agent（Qwen Code / Claude Code）
     * 发现并调用该客户端的工具。URL 中的 clientId 决定可见工具范围。
     *
     * <p>入参：
     * <ul>
     *   <li>路径参数 {@code clientId}：必填，客户端标识；</li>
     *   <li>查询参数 {@code endUserId} / {@code sessionId} / {@code chatId}：可选，
     *       用于把工具调用路由回具体用户、会话和本轮对话；</li>
     *   <li>请求体：JSON-RPC 2.0（{@link McpRequest}），支持
     *       {@code initialize}、{@code tools/list}、{@code tools/call}、{@code ping}。</li>
     * </ul>
     *
     * <p>返回：
     * <ul>
     *   <li>200 + JSON-RPC {@code result} 或 {@code error}；</li>
     *   <li>{@code tools/call} 的业务失败放在 {@code result.isError=true}，
     *       不返回 HTTP 5xx，让 Agent 自行决定是否继续；</li>
     *   <li>通知类消息（没有 {@code id}）返回 202，无响应体。</li>
     * </ul>
     */
    @PostMapping(value = "/mcp/{clientId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> handleForClient(
            @PathVariable String clientId,
            @RequestParam(value = "endUserId", required = false) String endUserId,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "chatId", required = false) String chatId,
            @RequestBody McpRequest request) {
        // 通知类消息（无 id）不需要响应
        if (request.id() == null) {
            return ResponseEntity.accepted().build();
        }
        String method = request.method() == null ? "" : request.method();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", request.id());

        switch (method) {
            case "initialize" -> response.put("result", initializeResult(request.params()));
            case "tools/list" -> response.put("result", toolsListResult(clientId));
            case "tools/call" -> response.put("result",
                    toolsCallResult(clientId, endUserId, sessionId, chatId, request.params()));
            case "ping" -> response.put("result", Map.of());
            default -> response.put("error", new McpError(-32601, "Method not found: " + method));
        }
        return ResponseEntity.ok(response);
    }

    /**
     * MCP initialize：回显客户端协议版本并声明 tools 能力。
     */
    private Map<String, Object> initializeResult(Map<String, Object> params) {
        String version = stringParam(params, "protocolVersion");
        if (version == null || version.isBlank()) {
            version = DEFAULT_PROTOCOL_VERSION;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", version);
        result.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        result.put("serverInfo", Map.of("name", "client-tools-mcp", "version", "0.1.0"));
        return result;
    }

    /**
     * MCP tools/list：把该客户端注册的工具目录转成 name/description/inputSchema。
     */
    private Map<String, Object> toolsListResult(String clientId) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolDefinition tool : clientRegistry.listTools(clientId)) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("name", tool.name());
            node.put("description", tool.description());
            node.put("inputSchema", tool.inputSchema() == null
                    ? Map.of("type", "object", "additionalProperties", false)
                    : tool.inputSchema());
            tools.add(node);
        }
        System.out.println("[Mcp] 客户端 " + clientId + " 可见工具 " + tools.size() + " 个");
        return Map.of("tools", tools);
    }

    /**
     * MCP tools/call：按 clientId/endUserId/sessionId/chatId 路由到客户端执行，
     * 把结果包装成 MCP content；业务失败放在 isError 里，不抛 5xx 给 Agent。
     */
    private Map<String, Object> toolsCallResult(String clientId,
                                                String endUserId,
                                                String sessionId,
                                                String chatId,
                                                Map<String, Object> params) {
        String name = stringParam(params, "name");
        Map<String, Object> arguments = mapParam(params, "arguments");
        System.out.println("[Mcp] 客户端 " + clientId + " 调用工具 " + name
                + "，endUserId=" + endUserId + "，sessionId=" + sessionId
                + "，chatId=" + chatId);

        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> content = new ArrayList<>();
        try {
            String text = clientRegistry.execute(
                    clientRegistry.newContext(clientId, endUserId, sessionId, chatId),
                    name, arguments);
            content.add(Map.of("type", "text", "text", text));
            result.put("content", content);
            result.put("isError", false);
        } catch (Exception e) {
            // 工具执行失败按 MCP 约定放在结果里返回，让 Agent 自行决定如何继续
            content.add(Map.of("type", "text", "text", "工具执行失败: " + e.getMessage()));
            result.put("content", content);
            result.put("isError", true);
        }
        return result;
    }

    private String stringParam(Map<String, Object> params, String key) {
        if (params == null) {
            return null;
        }
        Object value = params.get(key);
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapParam(Map<String, Object> params, String key) {
        if (params == null) {
            return Map.of();
        }
        Object value = params.get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
