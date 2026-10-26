package com.agent.demo.clientagent.acp_agent.impl.adaptor;

import com.agent.demo.clientagent.acp_agent.AcpAgent;
import com.agent.demo.clientagent.core.AgentRequest;
import com.agent.demo.clientagent.core.AgentSessionStore;
import com.agent.demo.clientagent.core.AgentResponse;
import com.agent.demo.clientagent.core.ToolCallContext;
import com.agent.demo.clientagent.client_registry.ClientRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自研Agent适配器：通过HTTP调用独立的 AgentBrain 服务。
 *
 * 职责边界：
 * - AgentBrain 只负责决策（返回工具调用或最终输出），不持有会话；
 * - 中间件持有会话历史，并在 Agent 请求工具调用时把调用路由到客户端，再把结果回传给 Agent。
 *
 * 这样自研Agent可以独立部署和替换，且客户端工具仍然只在客户端执行。
 */
@Component
public class SelfAgentAdaptor implements AcpAgent {

    /**
     * 中间件调用自研Agent服务(AgentBrain)的请求体。
     * 会话历史由中间件持有，只把本次决策需要的上下文传给Agent。
     */
    public record BrainChatRequest(String clientId,
                                   String endUserId,
                                   String sessionId,
                                   boolean newSession,
                                   String userPrompt,
                                   String clientPrompt) {
    }

    /**
     * 自研Agent服务(AgentBrain)的响应体。
     * type=CLIENT_TOOL_CALL 时需要 toolName/toolParameters；type=FINAL_OUTPUT 时需要 output。
     */
    public record BrainChatResponse(String type,
                                    String toolName,
                                    Map<String, String> toolParameters,
                                    String output) {
    }

    private static final String TYPE_TOOL_CALL = "CLIENT_TOOL_CALL";
    private static final String TYPE_FINAL_OUTPUT = "FINAL_OUTPUT";

    /** 会话归属：中间件持有，Brain 只做单次决策。 */
    private final AgentSessionStore sessionStore;
    /** 客户端工具目录与路由。 */
    private final ClientRegistry clientRegistry;
    /** 调用 Brain HTTP 接口。 */
    private final RestClient restClient = RestClient.create();
    /** AgentBrain 服务地址。 */
    private final String baseUrl;
    /** AgentBrain 决策接口路径。 */
    private final String chatPath;
    /** 单轮对话允许的最大工具调用轮数，防止死循环。 */
    private final int maxSteps;

    public SelfAgentAdaptor(AgentSessionStore sessionStore,
                            ClientRegistry clientRegistry,
                            @Value("${agent.self.url:http://localhost:8081}") String baseUrl,
                            @Value("${agent.self.chat-path:/v1/brain/chat}") String chatPath,
                            @Value("${agent.self.max-steps:5}") int maxSteps) {
        this.sessionStore = sessionStore;
        this.clientRegistry = clientRegistry;
        this.baseUrl = baseUrl != null && baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.chatPath = chatPath;
        this.maxSteps = Math.max(1, maxSteps);
    }

    @Override
    public String name() {
        return "self";
    }

    @Override
    public String description() {
        return "自研Agent服务(AgentBrain)，通过HTTP调用，可独立部署与替换";
    }

    @Override
    public List<String> capabilities() {
        return List.of("chat", "client-tool-call");
    }

    @Override
    public boolean available() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    @Override
    public String unavailableReason() {
        return available() ? null : "未配置 agent.self.url";
    }

    @Override
    public AgentResponse chat(AgentRequest request) {
        String clientId = request.clientId();
        boolean newSession = request.sessionId() == null || request.sessionId().isBlank();
        String localSessionId = sessionStore.resolveSessionId(
                clientId, request.endUserId(), request.sessionId());
        AgentSessionStore.SessionState state =
                sessionStore.getOrCreate(name(), clientId, request.endUserId(), localSessionId);

        String currentInput = request.userPrompt();
        for (int step = 0; step < maxSteps; step++) {
            boolean firstStepOfNewSession = step == 0 && newSession;
            BrainChatResponse response = callBrain(new BrainChatRequest(
                    clientId, request.endUserId(), localSessionId,
                    firstStepOfNewSession, currentInput, request.clientPrompt()));

            if (TYPE_FINAL_OUTPUT.equals(response.type())) {
                String output = response.output() == null ? "" : response.output();
                System.out.println("[Adaptor][self][client=" + clientId
                        + ",session=" + localSessionId + "] Agent Loop结束");
                return new AgentResponse(localSessionId, output);
            }

            if (!TYPE_TOOL_CALL.equals(response.type())) {
                throw new IllegalStateException("自研Agent返回未知结果类型: " + response.type());
            }

            System.out.println("[Adaptor][self][client=" + clientId
                    + ",session=" + localSessionId + "] Agent请求调用客户端工具: "
                    + response.toolName() + "，参数: " + response.toolParameters());
            // 工具调用由中间件路由到客户端执行，执行结果再回传给Agent继续决策
            // chatId 标识本轮对话，callId 标识本次工具调用，二者共同支撑多工具/多轮协议
            ToolCallContext callContext = clientRegistry.newContext(
                    clientId, request.endUserId(), localSessionId, request.chatId());
            String toolResult = clientRegistry.execute(
                    callContext, response.toolName(), toArguments(response.toolParameters()));
            currentInput = toolResult;
        }
        throw new IllegalStateException("自研Agent超过最大工具调用轮数(" + maxSteps + ")");
    }

    private BrainChatResponse callBrain(BrainChatRequest request) {
        try {
            BrainChatResponse response = restClient.post()
                    .uri(baseUrl + chatPath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(BrainChatResponse.class);
            if (response == null) {
                throw new IllegalStateException("自研Agent返回空响应");
            }
            return response;
        } catch (RestClientException e) {
            throw new IllegalStateException("调用自研Agent服务失败(" + baseUrl + chatPath
                    + ")，请确认 AgentBrain 已启动: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> toArguments(Map<String, String> parameters) {
        Map<String, Object> arguments = new HashMap<>();
        if (parameters != null) {
            arguments.putAll(parameters);
        }
        return arguments;
    }
}
