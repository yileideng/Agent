package com.agent.demo.clientagent.client;

import com.agent.demo.clientagent.agent_adaptor.AgentAdaptor;
import com.agent.demo.clientagent.agent_adaptor.AgentAdaptorRegistry;
import com.agent.demo.clientagent.agent_adaptor.AgentRequest;
import com.agent.demo.clientagent.agent_adaptor.ClientToolExecutor;
import com.agent.demo.clientagent.agent_brain.ChatResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端：本质就是一个外部客户端，只是跑在同一个进程里，用于内部测试。
 * 启动后通过统一的注册接口把自己的工具注册给服务端；Agent调用时由 /client/tools/execute 回调到这里执行。
 * 它和真实外部客户端走完全相同的协议，因此后续抽成独立进程时无需改动服务端。
 */
@Component
public class Client implements ClientToolExecutor {

    private final AgentAdaptorRegistry agentAdaptorRegistry;
    private final Map<String, ClientTool> clientTools;
    private final String port;
    private final String clientId;
    private final RestClient restClient = RestClient.create();

    public static String name = "张三";
    public static String clientPrompt = "客户端定制化system prompt";

    public Client(AgentAdaptorRegistry agentAdaptorRegistry, List<ClientTool> clientTools,
                  @Value("${server.port:8080}") String port,
                  @Value("${agent.default-client-id:in-app-client}") String clientId) {
        this.agentAdaptorRegistry = agentAdaptorRegistry;
        Map<String, ClientTool> tools = new HashMap<>();
        clientTools.forEach(tool -> tools.put(tool.getName(), tool));
        this.clientTools = Map.copyOf(tools);
        this.port = port;
        this.clientId = clientId;
        // 客户端把自己注册为工具执行器，供进程内Adaptor路由工具调用
        agentAdaptorRegistry.all().forEach(adaptor -> adaptor.setClientToolExecutor(this));
    }

    public String getClientId() {
        return clientId;
    }

    /**
     * 服务启动完成后，像真正的外部客户端那样通过注册接口上报工具和回调地址
     */
    @EventListener(ApplicationReadyEvent.class)
    public void registerToolsToServer() {
        List<Map<String, Object>> tools = clientTools.values().stream()
                .map(tool -> Map.<String, Object>of(
                        "name", tool.getName(),
                        "description", tool.getDescription(),
                        "parameters", tool.getParameters()))
                .toList();
        String baseUrl = "http://localhost:" + port;
        try {
            String response = restClient.post()
                    .uri(baseUrl + "/client-tools/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "clientId", clientId,
                            "callbackUrl", baseUrl + "/client/tools/execute",
                            "tools", tools))
                    .retrieve()
                    .body(String.class);
            System.out.println("[Client] 客户端 " + clientId + " 注册工具完成: " + response);
        } catch (Exception e) {
            System.out.println("[Client] 注册工具失败: " + e.getMessage());
        }
    }

    /**
     * 客户端发起对话，agentName指定使用哪个Agent，为空则用默认适配器
     */
    public ChatResult chat(String userPrompt, String agentName, String sessionId) {
        return agentAdaptorRegistry.get(agentName)
                .chat(new AgentRequest(clientId, userPrompt, clientPrompt, sessionId));
    }

    /**
     * 执行客户端本地工具，供工具回调接口调用
     */
    public Map<String, String> executeTool(String toolName, Map<String, String> parameters) {
        ClientTool tool = clientTools.get(toolName);
        if (tool == null) {
            throw new IllegalArgumentException("客户端 " + clientId + " 未提供工具: " + toolName);
        }
        return tool.execute(parameters);
    }

    @Override
    public ChatResult execute(AgentAdaptor adaptor, String toolName, Map<String, String> parameters,
                              AgentRequest request) {
        // 执行客户端工具
        System.out.println("[Client][client=" + clientId + "] 执行工具 " + toolName + "，参数: " + parameters);
        Map<String, String> toolResult = executeTool(toolName, parameters);
        // 执行结果回调，触发Agent继续执行
        System.out.println("[Client][client=" + clientId + "] 工具结果回调Agent: " + toolResult);
        return adaptor.chat(new AgentRequest(
                request.clientId(), toolResult.toString(), request.clientPrompt(), request.sessionId()));
    }
}
