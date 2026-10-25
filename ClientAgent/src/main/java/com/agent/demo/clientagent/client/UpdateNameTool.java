package com.agent.demo.clientagent.client;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class UpdateNameTool implements ClientTool {
    @Override
    public String getName() {
        return "update_name";
    }

    @Override
    public String getDescription() {
        return "修改当前登录用户的姓名，updated_name 参数为新姓名";
    }

    @Override
    public Map<String, String> getParameters() {
        Map<String, String> map = new HashMap<String, String>();
        map.put("updated_name", "string");
        return map;
    }

    @Override
    public Map<String, String> getRuntimeParameters() {
        return new HashMap<>();
    }

    @Override
    public Map<String, String> execute(Map<String, String> parameters) {
        System.out.println("UpdateNameTool params: " + parameters);
        System.out.println("UpdateNameTool execute");
        // 修改客户端存储的用户姓名信息
        Client.name = parameters.get("updated_name");
        Map<String, String> toolResult = new HashMap<>();
        System.out.println("UpdateNameTool result: " + toolResult);
        return toolResult;
    }
}
