package com.agent.demo.agentclient.client;

import com.agent.demo.agentclient.tool.ClientTool;
import com.agent.demo.agentsdk.AgentClient;
import com.agent.demo.agentsdk.ChatStreamListener;
import com.agent.demo.agentsdk.ChatStreamResult;
import com.agent.demo.agentsdk.Transport;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 移动端客户端模拟器：只负责声明工具和提供对话入口。
 *
 * <p>WebSocket 连接、工具注册、心跳、断线重连、callId 关联、并发执行
 * 全部由 {@link AgentClient}（SDK）处理；这里不再手写 WebSocket 监听器。
 *
 * <p>与 {@link Client}（SERVER 类型、HTTP 回调）的区别：
 * 手机 App 没有公网可达地址，改为客户端主动出站连接中间件 {@code /ws/agent}，
 * 中间件通过这条长连接下发工具调用。
 */
@Component
public class MobileClient {

    /** SDK 实例：负责 WS 连接、心跳、重连和工具分发。 */
    private final AgentClient agentClient;
    /** 移动端客户端唯一标识。 */
    private final String clientId;
    /** 当前终端用户标识。 */
    private final String endUserId;

    public MobileClient(List<ClientTool> clientTools,
                        Client client,
                        @Value("${agent-mobile.client-id:agent-mobile-client}") String clientId,
                        @Value("${agent-mobile.end-user-id:demo-user}") String endUserId,
                        @Value("${agent-mobile.ws-url:ws://localhost:8080/ws/agent}") String wsUrl,
                        @Value("${agent-client.middleware-url:http://localhost:8080}") String middlewareUrl,
                        @Value("${agent-mobile.client-prompt:移动端客户端定制化system prompt}") String clientPrompt) {
        this.clientId = clientId;
        this.endUserId = endUserId;

        AgentClient.Builder builder = AgentClient.builder()
                .gateway(middlewareUrl)
                .transport(Transport.WEBSOCKET)
                .wsUrl(wsUrl)
                .clientId(clientId)
                .endUserId(endUserId)
                .clientPrompt(clientPrompt);
        for (ClientTool tool : clientTools) {
            builder.tool(tool.getName(), tool.getDescription(), tool.getInputSchema(),
                    arguments -> client.executeTool(tool.getName(), arguments));
        }
        this.agentClient = builder.build();
    }

    /**
     * 启动即注册并建立 WebSocket；失败由 SDK 在后台重试。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            agentClient.connect();
            System.out.println("[AgentMobile] 移动端客户端就绪: clientId=" + clientId
                    + "，endUserId=" + endUserId);
        } catch (Exception e) {
            System.out.println("[AgentMobile] 初始化失败，SDK 会自动重试: " + e.getMessage());
        }
    }

    /**
     * 移动端对话入口：对话仍走 HTTP（客户端主动出站，穿透 NAT），
     * 工具调用经 WebSocket 长连接推回本客户端执行。
     */
    public String chat(String prompt, String agent, String sessionId) {
        return agentClient.chat(prompt, agent, sessionId);
    }

    /**
     * 流式对话：收到 delta 立即打印；工具调用仍走 WebSocket。
     */
    public ChatStreamResult chatStream(String prompt, String agent, String sessionId) {
        System.out.println("[AgentMobile][stream] 开始: " + prompt);
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
        System.out.println("[AgentMobile][stream] final: sessionId=" + result.sessionId()
                + "，chatId=" + result.chatId());
        return result;
    }

    public boolean isConnected() {
        return agentClient.isConnected();
    }

    @PreDestroy
    public void close() {
        agentClient.close();
    }
}
