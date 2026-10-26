package com.agent.demo.agentclient.tool;

import com.agent.demo.agentclient.client.Client;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
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
    /**
     * updated_name 必填；description 让模型知道这里要传“新的姓名”。
     */
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "updated_name", Map.of(
                                "type", "string",
                                "description", "新的姓名，例如：李四"
                        )
                ),
                "required", List.of("updated_name"),
                "additionalProperties", false
        );
    }

    @Override
    /**
     * 修改客户端本地保存的当前用户姓名。
     */
    public Map<String, Object> execute(Map<String, Object> parameters) {
        System.out.println("[AgentClient][UpdateNameTool] params: " + parameters);
        // 修改客户端存储的用户姓名信息
        Object updatedName = parameters.get("updated_name");
        Client.name = updatedName == null ? null : String.valueOf(updatedName);
        return new HashMap<>();
    }
}
