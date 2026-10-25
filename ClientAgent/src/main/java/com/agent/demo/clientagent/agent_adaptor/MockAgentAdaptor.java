package com.agent.demo.clientagent.agent_adaptor;

import com.agent.demo.clientagent.agent_brain.AgentBrain;
import com.agent.demo.clientagent.agent_brain.ChatResult;
import com.agent.demo.clientagent.agent_brain.ChatResultType;
import org.springframework.stereotype.Component;

/**
 * 本地mock适配器：内部走本地mock brain(AgentBrain)，用于验证客户端工具调用链
 */
@Component
public class MockAgentAdaptor implements AgentAdaptor {
    private final AgentBrain agentBrain = new AgentBrain();
    private ClientToolExecutor clientToolExecutor;

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public void setClientToolExecutor(ClientToolExecutor clientToolExecutor) {
        this.clientToolExecutor = clientToolExecutor;
    }

    @Override
    public ChatResult chat(String userPrompt, String clientPrompt, String sessionId) {
        // todo:这里暂时做成同步的，后续需要通过SSE推送ChatResult
        ChatResult chatResult = agentBrain.chat(userPrompt, clientPrompt, sessionId);
        if (chatResult.getResultType() == ChatResultType.CLIENT_TOOL_CALL) {
            // Brain请求调用客户端工具，路由给客户端执行，客户端执行完会回调chat继续Agent Loop
            System.out.println("[Adaptor] Brain请求调用客户端工具: " + chatResult.getToolName());
            return clientToolExecutor.execute(
                    this,
                    chatResult.getToolName(),
                    chatResult.getToolParameters(),
                    chatResult.getBrainSession().getSessionId());
        } else {
            // FINAL_OUTPUT，通知客户端Agent调用结束
            System.out.println("[Adaptor] Agent Loop结束");
            return chatResult;
        }
    }
}
