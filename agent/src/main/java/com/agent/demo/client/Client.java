package com.agent.demo.client;

import com.agent.demo.agent_adaptor.AgentAdaptor;
import com.agent.demo.agent_brain.ChatResult;

import java.util.Map;

public class Client {
    private AgentAdaptor agentAdaptor = new AgentAdaptor();
    public static String name = "张三";
    public static String clientPrompt = "客户端定制化system prompt";

    public Map<String, String> toolExecutor(String toolName, Map<String, String> parameters, String sessionId) {
        Map<String, ClientTool> clientToolMap = ToolRegistry.getClientToolMap();
        // 执行客户端工具
        Map<String, String> toolResult = clientToolMap.get(toolName).execute(parameters);
        // 执行结果回调，触发AgentBrain继续执行
        ChatResult chatResult = agentAdaptor.chat(toolResult.toString(), clientPrompt, sessionId);
        return null;
    }
}
