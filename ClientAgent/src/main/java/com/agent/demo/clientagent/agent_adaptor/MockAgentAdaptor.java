package com.agent.demo.clientagent.agent_adaptor;

import com.agent.demo.clientagent.agent_brain.AgentBrain;
import com.agent.demo.clientagent.agent_brain.ChatResult;
import com.agent.demo.clientagent.agent_brain.ChatResultType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 本地mock适配器：内部走本地mock brain(AgentBrain)，用于验证客户端工具调用链
 */
@Component
public class MockAgentAdaptor implements AgentAdaptor {

    private final AgentBrain agentBrain = new AgentBrain();
    private final AgentSessionStore sessionStore;
    private ClientToolExecutor clientToolExecutor;

    public MockAgentAdaptor(AgentSessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public String description() {
        return "本地Mock Agent，用于验证客户端工具调用链路，不依赖外部模型";
    }

    @Override
    public List<String> capabilities() {
        return List.of("chat", "client-tool-call");
    }

    @Override
    public void setClientToolExecutor(ClientToolExecutor clientToolExecutor) {
        this.clientToolExecutor = clientToolExecutor;
    }

    @Override
    public ChatResult chat(AgentRequest request) {
        boolean newSession = request.sessionId() == null || request.sessionId().isBlank();
        String localSessionId = sessionStore.resolveSessionId(request.clientId(), request.sessionId());
        AgentSessionStore.SessionState state =
                sessionStore.getOrCreate(name(), request.clientId(), localSessionId);

        // todo:这里暂时做成同步的，后续需要通过SSE推送ChatResult
        ChatResult chatResult = agentBrain.chat(
                state.brainSession(), request.userPrompt(), request.clientPrompt(), newSession);
        if (chatResult.getResultType() == ChatResultType.CLIENT_TOOL_CALL) {
            // Brain请求调用客户端工具，路由给客户端执行，客户端执行完会回调chat继续Agent Loop
            System.out.println("[Adaptor][mock][client=" + request.clientId()
                    + ",session=" + localSessionId + "] Brain请求调用客户端工具: " + chatResult.getToolName());
            AgentRequest followUp = new AgentRequest(
                    request.clientId(), request.userPrompt(), request.clientPrompt(), localSessionId);
            return clientToolExecutor.execute(
                    this, chatResult.getToolName(), chatResult.getToolParameters(), followUp);
        }
        // FINAL_OUTPUT，通知客户端Agent调用结束
        System.out.println("[Adaptor][mock][client=" + request.clientId()
                + ",session=" + localSessionId + "] Agent Loop结束");
        return chatResult;
    }
}
