package com.agent.demo.agentclient.tool;

import com.agent.demo.agentclient.client.Client;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端工具：查询当前登录用户姓名
 */
@Component
public class GetNameTool implements ClientTool {
    @Override
    public String getName() {
        return "get_name";
    }

    @Override
    public String getDescription() {
        return "获取当前登录用户的姓名，回答与用户身份相关的问题时调用";
    }

    @Override
    /**
     * 无参数：当前登录用户由 endUserId/session 隐式确定，不需要模型传参。
     */
    public Map<String, Object> getInputSchema() {
        return Map.of("type", "object", "additionalProperties", false);
    }

    @Override
    /**
     * 读取客户端本地保存的当前用户姓名。
     */
    public Map<String, Object> execute(Map<String, Object> parameters) {
        System.out.println("[AgentClient][GetNameTool] params: " + parameters);
        Map<String, Object> toolResult = new HashMap<>();
        // 查询客户端存储的用户姓名信息，并通过toolResult返回给Agent
        toolResult.put("name", Client.name);
        System.out.println("[AgentClient][GetNameTool] result: " + toolResult);
        return toolResult;
    }
}
