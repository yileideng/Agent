package com.agent.demo.clientagent.external;

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
 * 客户端工具注册入口：进程内的演示客户端和外部真实客户端都走同一个接口。
 * 一个客户端(clientId)拥有自己的工具目录，后续接入鉴权后 clientId 由凭证解析，不再信任请求体自报。
 */
@RestController
@RequestMapping("/client-tools")
public class ToolRegistrationController {

    private final ToolRegistry registry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ToolRegistrationController(ToolRegistry registry) {
        this.registry = registry;
    }

    /**
     * 请求体示例：
     * {
     *   "clientId": "xiaohongshu",
     *   "callbackUrl": "https://client.example.com/tools/execute",
     *   "tools": [{"name":"get_user_profile","description":"查当前用户资料","parameters":{"user_id":"string"}}]
     * }
     */
    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody String body) throws Exception {
        JsonNode request = objectMapper.readTree(body);
        String clientId = request.path("clientId").asText("").trim();
        String callbackUrl = request.path("callbackUrl").asText("").trim();
        if (clientId.isEmpty()) {
            throw new IllegalArgumentException("clientId不能为空");
        }
        if (callbackUrl.isEmpty()) {
            throw new IllegalArgumentException("callbackUrl不能为空");
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
            definitions.add(new ToolDefinition(
                    clientId,
                    node.path("name").asText(""),
                    node.path("description").asText(""),
                    parameterMap,
                    callbackUrl));
        }
        List<ToolDefinition> registered = registry.register(clientId, callbackUrl, definitions);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("clientId", clientId);
        response.put("registered", registered.size());
        response.put("tools", registered.stream().map(ToolDefinition::name).toList());
        return response;
    }

    /**
     * 查询已注册工具：传 clientId 只看该客户端，不传则返回全量（管理视角）
     */
    @GetMapping
    public List<ToolDefinition> list(@RequestParam(required = false) String clientId) {
        return clientId == null || clientId.isBlank() ? registry.listAll() : registry.list(clientId);
    }

    @GetMapping("/{clientId}/{name}")
    public ResponseEntity<ToolDefinition> getOne(@PathVariable String clientId, @PathVariable String name) {
        return ResponseEntity.of(registry.get(clientId, name));
    }

    /**
     * 注销单个工具，客户端调整工具集时使用
     */
    @DeleteMapping("/{clientId}/{name}")
    public Map<String, Object> unregister(@PathVariable String clientId, @PathVariable String name) {
        return Map.of("clientId", clientId, "name", name, "removed", registry.unregister(clientId, name));
    }

    /**
     * 注销某个客户端的全部工具，客户端下线时使用
     */
    @DeleteMapping("/{clientId}")
    public Map<String, Object> unregisterClient(@PathVariable String clientId) {
        return Map.of("clientId", clientId, "removed", registry.unregisterClient(clientId));
    }

    /**
     * 参数/回调地址校验失败统一返回400，便于客户端快速定位接入问题
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
