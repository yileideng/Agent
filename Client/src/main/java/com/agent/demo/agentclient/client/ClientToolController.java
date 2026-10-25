package com.agent.demo.agentclient.client;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端工具执行回调：中间件把 Agent 的工具调用转发到这里，由客户端真正执行本地工具。
 */
@RestController
public class ClientToolController {

    private final Client client;

    public ClientToolController(Client client) {
        this.client = client;
    }

    /**
     * 请求体：{"clientId":"agent-client","tool":"get_name","arguments":{"name_query":"current_user"}}
     * 响应体：工具执行结果 JSON；工具不存在时返回 {"error":"..."}
     */
    public record ToolExecuteRequest(String clientId, String tool, Map<String, String> arguments) {
    }

    @PostMapping("/client/tools/execute")
    public Map<String, String> execute(@RequestBody ToolExecuteRequest request) {
        System.out.println("[AgentClient] 收到工具调用: " + request.tool()
                + "，参数: " + request.arguments());
        try {
            Map<String, String> result = client.executeTool(request.tool(),
                    request.arguments() == null ? new HashMap<>() : request.arguments());
            System.out.println("[AgentClient] 工具执行结果: " + result);
            return result;
        } catch (IllegalArgumentException e) {
            return Map.of("error", e.getMessage());
        }
    }
}
