package com.agent.demo.agent_adaptor;

import com.agent.demo.agent_brain.AgentBrain;
import com.agent.demo.agent_brain.ChatResult;
import com.agent.demo.agent_brain.ChatResultType;

public class AgentAdaptor {
    private AgentBrain agentBrain = new AgentBrain();

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
            // 执行客户端工具调用
        } else if (chatResult.getResultType() == ChatResultType.FINAL_OUTPUT) {
            // 通知客户端Agent调用结束
        }
        return chatResult;
    }
}
