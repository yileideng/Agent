package com.agent.demo.clientagent.controller;

import com.agent.demo.clientagent.client_registry.ClientRegistry;
import com.agent.demo.clientagent.client_registry.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP Server(Streamable HTTP传输)：把注册的客户端工具暴露给支持MCP的Agent(如Qwen Code / Claude Code)。
 *
 * 客户端隔离入口：
 * - 推荐 POST /mcp/{clientId}，客户端在生成 mcp-config 时把自己的 clientId 拼进URL；
 * - 也支持 X-Client-Id 请求头；
 * - 都没有时使用默认客户端 agent.default-client-id。
 * 后续接入租户鉴权时，只需把 resolveClientId 换成从 session token 解析，MCP 协议无需改动。
 */
@RestController
public class McpServerController {

    private static final String DEFAULT_PROTOCOL_VERSION = "2025-03-26";

    private final ClientRegistry clientRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String defaultClientId;

    public McpServerController(ClientRegistry clientRegistry,
                               @Value("${agent.default-client-id:in-app-client}") String defaultClientId) {
        this.clientRegistry = clientRegistry;
        this.defaultClientId = defaultClientId;
    }

    /**
     * POST /mcp
     * MCP 默认入口：clientId 优先取 X-Client-Id 请求头，未传时使用 agent.default-client-id。
     * 请求体是标准 JSON-RPC，支持 initialize / tools/list / tools/call / ping。
     * 移动端客户端场景可由查询参数 endUserId / sessionId 指定路由身份。
     * 返回：JSON-RPC 响应；通知类消息（无 id）返回 202。
     */
    @PostMapping(value = "/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> handleDefault(
            @RequestHeader(value = "X-Client-Id", required = false) String headerClientId,
            @RequestParam(value = "endUserId", required = false) String endUserId,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestBody String body) throws Exception {
        return handle(resolveClientId(null, headerClientId), endUserId, sessionId, body);
    }

    /**
     * POST /mcp/{clientId}
     * MCP 客户端隔离入口：URL 中的 clientId 决定本次请求只能看到/调用该客户端的工具。
     * CLI Agent 的 mcp-config 使用该地址（{agent.cli.mcp-url}/{clientId}）。
     * 查询参数 endUserId / sessionId 用于把 MOBILE 客户端的工具调用路由回具体 WS 连接。
     */
    @PostMapping(value = "/mcp/{clientId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> handleForClient(
            @PathVariable String clientId,
            @RequestHeader(value = "X-Client-Id", required = false) String headerClientId,
            @RequestParam(value = "endUserId", required = false) String endUserId,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestBody String body) throws Exception {
        return handle(resolveClientId(clientId, headerClientId), endUserId, sessionId, body);
    }

    /**
     * 请求体用String接收再自行解析：Spring Boot 4 的HTTP转换器使用Jackson 3，与这里的Jackson 2 JsonNode不兼容
     */
    private ResponseEntity<String> handle(String clientId, String endUserId, String sessionId,
                                          String body) throws Exception {
        JsonNode request = objectMapper.readTree(body);
        JsonNode id = request.get("id");
        // 通知类消息(无id)不需要响应
        if (id == null || id.isNull()) {
            return ResponseEntity.accepted().build();
        }
        String method = request.path("method").asText("");
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);

        switch (method) {
            case "initialize" -> response.set("result", initializeResult(request));
            case "tools/list" -> response.set("result", toolsListResult(clientId));
            case "tools/call" -> response.set("result",
                    toolsCallResult(clientId, endUserId, sessionId, request.path("params")));
            case "ping" -> response.set("result", objectMapper.createObjectNode());
            default -> response.set("error", error(-32601, "Method not found: " + method));
        }
        return ResponseEntity.ok(objectMapper.writeValueAsString(response));
    }

    private ObjectNode initializeResult(JsonNode request) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion",
                request.path("params").path("protocolVersion").asText(DEFAULT_PROTOCOL_VERSION));
        result.putObject("capabilities").putObject("tools").put("listChanged", false);
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", "client-tools-mcp");
        serverInfo.put("version", "0.0.2");
        return result;
    }

    private ObjectNode toolsListResult(String clientId) {
        ArrayNode tools = objectMapper.createArrayNode();
        for (ToolDefinition tool : clientRegistry.listTools(clientId)) {
            ObjectNode node = tools.addObject();
            node.put("name", tool.name());
            node.put("description", tool.description());
            ObjectNode schema = node.putObject("inputSchema");
            schema.put("type", "object");
            ObjectNode properties = schema.putObject("properties");
            ArrayNode required = schema.putArray("required");
            tool.parameters().forEach((name, type) -> {
                properties.putObject(name).put("type", type);
                required.add(name);
            });
        }
        System.out.println("[Mcp] 客户端 " + clientId + " 可见工具 " + tools.size() + " 个");
        ObjectNode result = objectMapper.createObjectNode();
        result.set("tools", tools);
        return result;
    }

    private ObjectNode toolsCallResult(String clientId, String endUserId, String sessionId,
                                       JsonNode params) {
        String name = params.path("name").asText();
        Map<String, Object> arguments = new HashMap<>();
        JsonNode argumentsNode = params.path("arguments");
        if (argumentsNode.isObject()) {
            arguments.putAll(objectMapper.convertValue(argumentsNode, Map.class));
        }
        System.out.println("[Mcp] 客户端 " + clientId + " 调用工具 " + name
                + "，endUserId=" + endUserId + "，sessionId=" + sessionId);
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        try {
            String text = clientRegistry.execute(clientId, endUserId, sessionId, name, arguments);
            content.addObject().put("type", "text").put("text", text);
            result.put("isError", false);
        } catch (Exception e) {
            // 工具执行失败按MCP约定放在结果里返回，让Agent自行决定如何继续
            content.addObject().put("type", "text").put("text", "工具执行失败: " + e.getMessage());
            result.put("isError", true);
        }
        return result;
    }

    private String resolveClientId(String pathClientId, String headerClientId) {
        if (pathClientId != null && !pathClientId.isBlank()) {
            return pathClientId;
        }
        if (headerClientId != null && !headerClientId.isBlank()) {
            return headerClientId;
        }
        return defaultClientId;
    }

    private ObjectNode error(int code, String message) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        return error;
    }
}
