package com.agent.demo.clientagent.client;

import com.agent.demo.clientagent.agent_adaptor.AgentAdaptor;
import com.agent.demo.clientagent.agent_adaptor.ClientToolExecutor;
import com.agent.demo.clientagent.agent_brain.ChatResult;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class Client implements ClientToolExecutor {
    private final AgentAdaptor agentAdaptor;
    private final List<ClientTool> clientTools;
    public static String name = "张三";
    public static String clientPrompt = "客户端定制化system prompt";

    public Client(AgentAdaptor agentAdaptor, List<ClientTool> clientTools) {
        this.agentAdaptor = agentAdaptor;
        this.clientTools = clientTools;
        // 客户端把自己注册为工具执行器，供Adaptor路由工具调用
        agentAdaptor.setClientToolExecutor(this);
    }

    /**
     * 初始化：注册所有客户端工具(Spring容器中的ClientTool实现)
     */
    @PostConstruct
    public void init() {
        clientTools.forEach(ToolRegistry::registerClientTool);
    }

    /**
     * 客户端发起对话，sessionId为空表示新会话
     */
    public ChatResult chat(String userPrompt) {
        return agentAdaptor.chat(userPrompt, clientPrompt, null);
    }

    @Override
    public ChatResult execute(String toolName, Map<String, String> parameters, String sessionId) {
        // 执行客户端工具
        System.out.println("[Client] 执行工具 " + toolName + "，参数: " + parameters);
        ClientTool tool = ToolRegistry.getClientToolMap().get(toolName);
        Map<String, String> toolResult = tool.execute(parameters);
        // 执行结果回调，触发AgentBrain继续执行
        System.out.println("[Client] 工具结果回调Brain: " + toolResult);
        return agentAdaptor.chat(toolResult.toString(), clientPrompt, sessionId);
    }
}
