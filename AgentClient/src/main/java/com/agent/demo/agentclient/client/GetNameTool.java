package com.agent.demo.agentclient.client;

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
    public Map<String, String> getParameters() {
        Map<String, String> map = new HashMap<>();
        map.put("name_query", "string");
        return map;
    }

    @Override
    public Map<String, String> getReturnParameters() {
        Map<String, String> map = new HashMap<>();
        map.put("name", "string");
        return map;
    }

    @Override
    public Map<String, String> execute(Map<String, String> parameters) {
        System.out.println("[AgentClient][GetNameTool] params: " + parameters);
        Map<String, String> toolResult = new HashMap<>();
        // 查询客户端存储的用户姓名信息，并通过toolResult返回给Agent
        toolResult.put("name", Client.name);
        System.out.println("[AgentClient][GetNameTool] result: " + toolResult);
        return toolResult;
    }
}
