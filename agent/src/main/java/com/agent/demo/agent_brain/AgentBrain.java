package com.agent.demo.agent_brain;

public class AgentBrain {

    public ChatResult chat(String userPrompt, String clientPrompt, String sessionId) {
        ChatResult result = new ChatResult();
        // todo:后续brainSession做成线程内部共享，线程间独立的
        BrainSession brainSession = new BrainSession();
        brainSession.initSession(sessionId);
        if (sessionId == null || sessionId.isEmpty()) {
            // todo:sessionId为空，代表是第一次对话，第一次对话先测试工具调用
            result.setResultType(ChatResultType.CLIENT_TOOL_CALL);
        } else {
            // sessionId不为空，说明是工具执行结果的回调，终止Agent Loop
            result.setResultType(ChatResultType.FINAL_OUTPUT);
        }
        result.setBrainSession(brainSession);
        return result;
    }
}
