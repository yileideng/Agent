package com.agent.demo.clientagent.client_tool;

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
     * POST /client-tools/register
     * 客户端把自己的工具清单和回调地址上报给中间件，服务端按 (clientId, name) 建立/覆盖工具目录。
     * 请求体示例：
     * {
     *   "clientId": "xiaohongshu",
     *   "callbackUrl": "https://client.example.com/tools/execute",
     *   "tools": [{"name":"get_user_profile","description":"查当前用户资料","parameters":{"user_id":"string"}}]
     * }
     * 返回：clientId、注册数量 registered、工具名列表 tools；参数非法时返回 400。
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
     * GET /client-tools?clientId=xxx
     * 查询已注册工具：带 clientId 只返回该客户端的工具，不带则返回所有客户端的全量工具（管理视角）。
     */
    @GetMapping
    public List<ToolDefinition> list(@RequestParam(required = false) String clientId) {
        return clientId == null || clientId.isBlank() ? registry.listAll() : registry.list(clientId);
    }

    /**
     * GET /client-tools/{clientId}/{name}
     * 查询某个客户端下的单个工具定义；不存在时返回 404。
     */
    @GetMapping("/{clientId}/{name}")
    public ResponseEntity<ToolDefinition> getOne(@PathVariable String clientId, @PathVariable String name) {
        return ResponseEntity.of(registry.get(clientId, name));
    }

    /**
     * DELETE /client-tools/{clientId}/{name}
     * 注销单个工具，客户端调整工具集时使用；返回 removed 表示是否确实删除。
     */
    @DeleteMapping("/{clientId}/{name}")
    public Map<String, Object> unregister(@PathVariable String clientId, @PathVariable String name) {
        return Map.of("clientId", clientId, "name", name, "removed", registry.unregister(clientId, name));
    }

    /**
     * DELETE /client-tools/{clientId}
     * 注销某个客户端的全部工具（客户端下线/租户回收时使用）；返回 removed 为删除数量。
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
