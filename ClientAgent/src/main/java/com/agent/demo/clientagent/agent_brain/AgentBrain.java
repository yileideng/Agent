package com.agent.demo.clientagent.agent_brain;

import java.util.Map;

/**
 * 本地mock brain：固定走一次客户端工具调用后结束，仅用于验证调用链。
 * 现在不持有会话状态，会话由适配器层的 AgentSessionStore 传入，符合"会话由适配器持有"的约束。
 * 由 MockAgentAdaptor 内部使用。
 */
public class AgentBrain {

    /**
     * @param brainSession 适配器持有的会话
     * @param userPrompt   用户输入，或工具执行结果
     * @param newSession   是否为首轮对话
     */
    public ChatResult chat(BrainSession brainSession, String userPrompt, String clientPrompt, boolean newSession) {
        ChatResult result = new ChatResult();
        if (newSession) {
            // 第一次对话：mock决策——请求调用客户端工具get_name
            brainSession.addMessage(new Message("user", userPrompt));
            result.setResultType(ChatResultType.CLIENT_TOOL_CALL);
            result.setToolName("get_name");
            result.setToolParameters(Map.of("name_query", "current_user"));
        } else {
            // 工具执行结果的回调：产出最终输出，终止Agent Loop
            brainSession.addMessage(new Message("tool", userPrompt));
            result.setResultType(ChatResultType.FINAL_OUTPUT);
            result.setOutput("已获取到用户信息 " + userPrompt + "，回答用户的问题");
        }
        result.setBrainSession(brainSession);
        return result;
    }
}
