package com.agent.demo.agentclient.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自研客户端：
 * 1. 启动后把本地工具注册到中间件（失败会定时重试，解决客户端先于中间件启动的问题）；
 * 2. 接收中间件的工具执行回调，在本地执行工具；
 * 3. 作为客户端向中间件 /chat 发起对话，中间件再调用自研Agent服务。
 */
@Component
public class Client {

    private final String clientId;
    private final String middlewareUrl;
    private final String callbackBaseUrl;
    private final String clientPrompt;
    private final Map<String, ClientTool> clientTools;
    private final RestClient restClient = RestClient.create();

    /** 客户端本地维护的用户数据，仅用于演示 */
    public static String name = "张三";

    private volatile boolean registered;

    public Client(List<ClientTool> clientTools,
                  @Value("${agent-client.client-id:agent-client}") String clientId,
                  @Value("${agent-client.middleware-url:http://localhost:8080}") String middlewareUrl,
                  @Value("${agent-client.callback-base-url:http://localhost:8082}") String callbackBaseUrl,
                  @Value("${agent-client.client-prompt:客户端定制化system prompt}") String clientPrompt) {
        Map<String, ClientTool> tools = new HashMap<>();
        clientTools.forEach(tool -> tools.put(tool.getName(), tool));
        this.clientTools = Map.copyOf(tools);
        this.clientId = clientId;
        this.middlewareUrl = trimTrailingSlash(middlewareUrl);
        this.callbackBaseUrl = trimTrailingSlash(callbackBaseUrl);
        this.clientPrompt = clientPrompt;
    }

    /**
     * 启动即注册；失败时由定时任务重试
     */
    @EventListener(ApplicationReadyEvent.class)
    public void registerOnStartup() {
        tryRegister();
    }

    @Scheduled(fixedDelayString = "${agent-client.register-retry-ms:5000}")
    public void retryRegister() {
        if (!registered) {
            tryRegister();
        }
    }

    private void tryRegister() {
        List<Map<String, Object>> tools = clientTools.values().stream()
                .map(tool -> Map.<String, Object>of(
                        "name", tool.getName(),
                        "description", tool.getDescription(),
                        "parameters", tool.getParameters()))
                .toList();
        String callbackUrl = callbackBaseUrl + "/client/tools/execute";
        try {
            // 调用AgentAdaptor的接口，把客户端工具注册到ToolRegistry，再通过McpServerController把可用的客户端工具暴露给Agent
            String response = restClient.post()
                    .uri(middlewareUrl + "/client-tools/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "clientId", clientId,
                            "callbackUrl", callbackUrl,
                            "tools", tools))
                    .retrieve()
                    .body(String.class);
            registered = true;
            System.out.println("[AgentClient] 客户端 " + clientId + " 注册工具完成: " + response);
        } catch (Exception e) {
            System.out.println("[AgentClient] 注册工具失败，稍后重试: " + e.getMessage());
        }
    }

    /**
     * 客户端向中间件发起对话，返回中间件的原始JSON响应
     */
    public String chat(String prompt, String agent, String sessionId) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(middlewareUrl)
                .path("/chat")
                .queryParam("clientId", clientId)
                .queryParam("prompt", prompt);
        if (agent != null && !agent.isBlank()) {
            builder.queryParam("agent", agent);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            builder.queryParam("sessionId", sessionId);
        }
        if (clientPrompt != null && !clientPrompt.isBlank()) {
            builder.queryParam("clientPrompt", clientPrompt);
        }
        return restClient.get()
                .uri(builder.build().encode().toUri())
                .retrieve()
                .body(String.class);
    }

    /**
     * 在客户端本地执行工具，供中间件的工具回调接口调用
     */
    public Map<String, String> executeTool(String toolName, Map<String, String> parameters) {
        ClientTool tool = clientTools.get(toolName);
        if (tool == null) {
            throw new IllegalArgumentException("客户端 " + clientId + " 未提供工具: " + toolName);
        }
        return tool.execute(parameters == null ? Map.of() : parameters);
    }

    private String trimTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
