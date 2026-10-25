package com.agent.demo.clientagent.controller;

import com.agent.demo.clientagent.client_tool.ClientRegistration;
import com.agent.demo.clientagent.client_tool.ClientRegistry;
import com.agent.demo.clientagent.client_tool.ClientType;
import com.agent.demo.clientagent.client_tool.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端注册与工具管理入口：服务端客户端和移动端客户端都走同一套接口。
 * 一个客户端(clientId)拥有自己的工具目录，后续接入鉴权后 clientId 由凭证解析，不再信任请求体自报。
 */
@RestController
@RequestMapping("/clients")
public class ClientToolController {

    private final ClientRegistry clientRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ClientToolController(ClientRegistry clientRegistry) {
        this.clientRegistry = clientRegistry;
    }

    /**
     * POST /clients/register
     * 客户端把自己的类型、回调基地址、工具清单上报给中间件，服务端按 (clientId, name) 建立/覆盖工具目录。
     * 请求体示例（SERVER 客户端）：
     * {
     *   "clientId": "xiaohongshu",
     *   "clientType": "SERVER",
     *   "baseCallbackUrl": "https://client.example.com",
     *   "defaultCallbackPath": "/client/tools/execute",
     *   "clientPrompt": "你是小红书的创作助手...",
     *   "tools": [
     *     {"name":"get_user_profile","description":"查当前用户资料","parameters":{"user_id":"string"}}
     *   ]
     * }
     * MOBILE 客户端不需要 baseCallbackUrl 和 callbackPath。
     * 返回：clientId、clientType、注册数量 registered、工具名列表 tools；参数非法时返回 400。
     */
    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody String body) throws Exception {
        JsonNode request = objectMapper.readTree(body);
        String clientId = request.path("clientId").asText("").trim();
        String clientTypeStr = request.path("clientType").asText("").trim();
        String baseCallbackUrl = request.path("baseCallbackUrl").asText(null);
        String defaultCallbackPath = request.path("defaultCallbackPath").asText(null);
        String clientPrompt = request.path("clientPrompt").asText(null);

        if (clientId.isEmpty()) {
            throw new IllegalArgumentException("clientId不能为空");
        }
        ClientType clientType;
        try {
            clientType = ClientType.valueOf(clientTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("clientType非法，必须是 SERVER 或 MOBILE: " + clientTypeStr);
        }

        JsonNode toolsNode = request.path("tools");
        if (!toolsNode.isArray() || toolsNode.isEmpty()) {
            throw new IllegalArgumentException("tools不能为空");
        }
        List<ToolDefinition> definitions = new ArrayList<>();
        for (JsonNode node : toolsNode) {
            JsonNode parameters = node.path("parameters");
            Map<String, String> parameterMap = new HashMap<>();
            parameters.fields().forEachRemaining(
                    entry -> parameterMap.put(entry.getKey(), entry.getValue().asText("string")));
            String callbackPath = node.path("callbackPath").asText(null);
            definitions.add(new ToolDefinition(
                    node.path("name").asText(""),
                    node.path("description").asText(""),
                    parameterMap,
                    callbackPath));
        }
        ClientRegistration registered = clientRegistry.register(
                clientId, clientType, baseCallbackUrl, defaultCallbackPath, clientPrompt, definitions);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("clientId", registered.clientId());
        response.put("clientType", registered.clientType().name());
        response.put("registered", registered.tools().size());
        response.put("tools", registered.tools().stream().map(ToolDefinition::name).toList());
        return response;
    }

    /**
     * GET /clients
     * 查询已注册客户端列表。
     */
    @GetMapping
    public List<Map<String, Object>> listClients() {
        return clientRegistry.listClients().stream().map(client -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("clientId", client.clientId());
            item.put("clientType", client.clientType().name());
            item.put("baseCallbackUrl", client.baseCallbackUrl());
            item.put("toolCount", client.tools().size());
            item.put("tools", client.tools().stream().map(ToolDefinition::name).toList());
            item.put("status", client.status());
            return item;
        }).toList();
    }

    /**
     * GET /clients/{clientId}/tools
     * 查询某个客户端已注册的全部工具。
     */
    @GetMapping("/{clientId}/tools")
    public List<ToolDefinition> listTools(@PathVariable String clientId) {
        return clientRegistry.listTools(clientId);
    }

    /**
     * GET /clients/{clientId}/tools/{name}
     * 查询某个客户端下的单个工具定义；不存在时返回 404。
     */
    @GetMapping("/{clientId}/tools/{name}")
    public ResponseEntity<ToolDefinition> getOne(@PathVariable String clientId, @PathVariable String name) {
        return ResponseEntity.of(clientRegistry.getTool(clientId, name));
    }

    /**
     * DELETE /clients/{clientId}/tools/{name}
     * 注销单个工具，客户端调整工具集时使用；返回 removed 表示是否确实删除。
     */
    @DeleteMapping("/{clientId}/tools/{name}")
    public Map<String, Object> unregisterTool(@PathVariable String clientId, @PathVariable String name) {
        return Map.of("clientId", clientId, "name", name,
                "removed", clientRegistry.unregisterTool(clientId, name));
    }

    /**
     * DELETE /clients/{clientId}
     * 注销某个客户端（客户端下线/租户回收时使用）；返回 removed 为删除的工具数量。
     */
    @DeleteMapping("/{clientId}")
    public Map<String, Object> unregisterClient(@PathVariable String clientId) {
        return Map.of("clientId", clientId, "removed", clientRegistry.unregisterClient(clientId));
    }

    /**
     * 参数/回调地址校验失败统一返回400，便于客户端快速定位接入问题
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
