package com.agent.demo.clientagent.agent_adaptor;

import com.agent.demo.clientagent.agent_brain.AgentBrain;
import com.agent.demo.clientagent.agent_brain.ChatResult;
import com.agent.demo.clientagent.agent_brain.ChatResultType;
import org.springframework.stereotype.Component;

@Component
public class AgentAdaptor {
    private final AgentBrain agentBrain;
    private ClientToolExecutor clientToolExecutor;

    public AgentAdaptor(AgentBrain agentBrain) {
        this.agentBrain = agentBrain;
    }

    public void setClientToolExecutor(ClientToolExecutor clientToolExecutor) {
        this.clientToolExecutor = clientToolExecutor;
    }

    /**
     * 执行Agent对话，触发方是客户端，内部调用AgentBrain执行
     * @param userPrompt 用户提示词
     * @param clientPrompt 客户端定制的系统提示词
     * @param sessionId 对话的sessionID
     * @return
     */
    public ChatResult chat(String userPrompt, String clientPrompt, String sessionId) {
        // todo:这里暂时做成同步的，后续需要通过SSE推送ChatResult
        ChatResult chatResult = agentBrain.chat(userPrompt, clientPrompt, sessionId);
        if (chatResult.getResultType() == ChatResultType.CLIENT_TOOL_CALL) {
            // Brain请求调用客户端工具，路由给客户端执行，客户端执行完会回调chat继续Agent Loop
            System.out.println("[Adaptor] Brain请求调用客户端工具: " + chatResult.getToolName());
            return clientToolExecutor.execute(
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
