package com.agent.demo.agentclient.client;

import com.agent.demo.agentsdk.AgentClient;
import com.agent.demo.agentsdk.ChatStreamListener;
import com.agent.demo.agentsdk.ChatStreamResult;
import com.agent.demo.agentsdk.Transport;
import com.agent.demo.agentclient.tool.ClientTool;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自研 SERVER 客户端：只声明工具和业务入口，注册/对话/工具分发全部交给 SDK。
 *
 * <p>与移动端 {@link MobileClient} 使用同一个
 * {@link AgentClient}，唯一差别是 {@link Transport#HTTP_CALLBACK}：
 * 网关通过 HTTP 回调下发 tool_call，SDK 负责解析、执行、生成 tool_result。
 */
@Component
public class Client {

    /** SDK 实例：负责注册、HTTP 回调、工具分发和对话。 */
    private final AgentClient agentClient;
    /** 客户端唯一标识。 */
    private final String clientId;
    /** 本地工具：name -> 实现。 */
    private final Map<String, ClientTool> clientTools;

    /** 客户端本地维护的用户数据，仅用于演示；真实客户端应按 endUserId 隔离。 */
    public static String name = "张三";

    public Client(List<ClientTool> clientTools,
                  @Value("${agent-client.client-id:agent-client}") String clientId,
                  @Value("${agent-client.end-user-id:demo-user}") String endUserId,
                  @Value("${agent-client.middleware-url:http://localhost:8080}") String middlewareUrl,
                  @Value("${agent-client.callback-base-url:http://localhost:8082}") String callbackBaseUrl,
                  @Value("${agent-client.client-prompt:客户端定制化system prompt}") String clientPrompt) {
        Map<String, ClientTool> tools = new HashMap<>();
        clientTools.forEach(tool -> tools.put(tool.getName(), tool));
        this.clientTools = Map.copyOf(tools);
        this.clientId = clientId;

        AgentClient.Builder builder = AgentClient.builder()
                .gateway(middlewareUrl)
                .transport(Transport.HTTP_CALLBACK)
                .callbackBaseUrl(callbackBaseUrl)
                .clientId(clientId)
                .endUserId(endUserId)
                .clientPrompt(clientPrompt);
        for (ClientTool tool : clientTools) {
            builder.tool(tool.getName(), tool.getDescription(), tool.getInputSchema(),
                    arguments -> executeTool(tool.getName(), arguments));
        }
        this.agentClient = builder.build();
    }

    /**
     * 启动即注册；失败由 SDK 在后台重试。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            agentClient.connect();
            System.out.println("[AgentClient] SERVER 客户端就绪: clientId=" + clientId);
        } catch (Exception e) {
            System.out.println("[AgentClient] 初始化失败，SDK 会自动重试: " + e.getMessage());
        }
    }

    /**
     * 客户端向中间件发起对话，返回 final 信封 JSON。
     */
    public String chat(String prompt, String agent, String sessionId) {
        return agentClient.chat(prompt, agent, sessionId);
    }

    /**
     * 流式对话：收到 delta 立即打印，收到 thinking 加前缀打印。
     *
     * <p>这里用控制台输出模拟真实客户端的增量渲染；工具调用仍走 HTTP 回调。
     */
    public ChatStreamResult chatStream(String prompt, String agent, String sessionId) {
        System.out.println("[AgentClient][stream] 开始: " + prompt);
        ChatStreamResult result = agentClient.chatStream(prompt, agent, sessionId,
                new ChatStreamListener() {
                    @Override
                    public void onDelta(String text) {
                        System.out.print(text);
                        System.out.flush();
                    }

                    @Override
                    public void onThinking(String text) {
                        System.out.print("[thinking] " + text);
                        System.out.flush();
                    }
                });
        // 正文已经通过 onDelta 实时打印，这里只打印结束标记，避免重复输出整段回答
        System.out.println();
        System.out.println("[AgentClient][stream] final: sessionId=" + result.sessionId()
                + "，chatId=" + result.chatId());
        return result;
    }

    /**
     * HTTP 回调入口：中间件把 tool_call 信封 POST 过来，SDK 解析并返回 tool_result。
     */
    public String handleToolCall(String body) {
        return agentClient.handleToolCall(body);
    }

    /**
     * 客户端本地执行工具，供 SDK 的 ToolHandler 调用。
     */
    public Map<String, Object> executeTool(String toolName, Map<String, Object> parameters) {
        ClientTool tool = clientTools.get(toolName);
        if (tool == null) {
            throw new IllegalArgumentException("客户端 " + clientId + " 未提供工具: " + toolName);
        }
        return tool.execute(parameters == null ? Map.of() : parameters);
    }

    @PreDestroy
    public void close() {
        agentClient.close();
    }
}
