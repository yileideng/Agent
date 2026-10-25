package com.agent.demo.clientagent.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端工具执行回调接口：Agent通过MCP调用客户端工具时，服务端把请求转发到这里，
 * 由客户端真正执行本地工具，结果作为HTTP响应返回给Agent。
 */
@RestController
public class ClientToolController {

    private final Client client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ClientToolController(Client client) {
        this.client = client;
    }

    /**
     * 请求体：{"tool":"get_name","arguments":{...}}
     */
    @PostMapping(value = "/client/tools/execute", produces = MediaType.APPLICATION_JSON_VALUE)
    public String execute(@RequestBody String body) throws Exception {
        JsonNode request = objectMapper.readTree(body);
        String toolName = request.path("tool").asText();
        Map<String, String> parameters = new HashMap<>();
        request.path("arguments").fields().forEachRemaining(
                entry -> parameters.put(entry.getKey(), entry.getValue().asText()));

        System.out.println("[Client] 收到Agent的工具调用: " + toolName + "，参数: " + parameters);
        try {
            Map<String, String> toolResult = client.executeTool(toolName, parameters);
            System.out.println("[Client] 工具执行结果: " + toolResult);
            return objectMapper.writeValueAsString(toolResult);
        } catch (IllegalArgumentException e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
