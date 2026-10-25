package com.agent.demo.agent_brain;

import java.util.HashMap;
import java.util.Map;

public class AgentBrain {
    // session存储，demo先用内存Map，实际应落库
    private final Map<String, BrainSession> sessions = new HashMap<>();

    public ChatResult chat(String userPrompt, String clientPrompt, String sessionId) {
        ChatResult result = new ChatResult();
        BrainSession brainSession;
        if (sessionId == null || sessionId.isEmpty()) {
            // 第一次对话：初始化session，mock决策——请求调用客户端工具get_name
            brainSession = new BrainSession().initSession(null);
            sessions.put(brainSession.getSessionId(), brainSession);
            brainSession.addMessage(new Message("user", userPrompt));
            result.setResultType(ChatResultType.CLIENT_TOOL_CALL);
            result.setToolName("get_name");
            result.setToolParameters(Map.of("name_query", "current_user"));
        } else {
            // 工具执行结果的回调：恢复session，产出最终输出，终止Agent Loop
            brainSession = sessions.get(sessionId);
            brainSession.addMessage(new Message("tool", userPrompt));
            result.setResultType(ChatResultType.FINAL_OUTPUT);
            result.setOutput("已获取到用户信息 " + userPrompt + "，回答用户的问题");
        }
        result.setBrainSession(brainSession);
        return result;
    }
}
