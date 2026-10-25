package com.agent.demo.client;

import java.util.HashMap;
import java.util.Map;

public class GetNameTool implements ClientTool {
    @Override
    public String getName() {
        return "GetNameTool Name";
    }

    @Override
    public String getDescription() {
        return "GetNameTool Description";
    }

    @Override
    public Map<String, String> getParameters() {
        Map<String, String> map = new HashMap<String, String>();
        map.put("name_query", "string");
        return map;
    }

    @Override
    public Map<String, String> getRuntimeParameters() {
        Map<String, String> map = new HashMap<>();
        map.put("name", "string");
        return map;
    }

    @Override
    public Map<String, String> execute(Map<String, String> parameters) {
        System.out.println("GetNameTool params: " + parameters);
        System.out.println("GetNameTool execute");
        Map<String, String> toolResult = new HashMap<>();
        // 查询客户端存储的用户姓名信息，并通过toolResult返回给AgentBrain
        toolResult.put("name", Client.name);
        System.out.println("GetNameTool result: " + toolResult);
        return toolResult;
    }
}
