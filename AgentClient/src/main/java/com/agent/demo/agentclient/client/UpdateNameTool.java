package com.agent.demo.agentclient.client;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端工具：修改当前登录用户姓名
 */
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
        Map<String, String> map = new HashMap<>();
        map.put("updated_name", "string");
        return map;
    }

    @Override
    public Map<String, String> getReturnParameters() {
        return new HashMap<>();
    }

    @Override
    public Map<String, String> execute(Map<String, String> parameters) {
        System.out.println("[AgentClient][UpdateNameTool] params: " + parameters);
        // 修改客户端存储的用户姓名信息
        Client.name = parameters.get("updated_name");
        return new HashMap<>();
    }
}
