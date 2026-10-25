package com.agent.demo.agent_brain;

public class ChatResult {
    // 包括Brain和Client的共享变量、消息列表等等
    private BrainSession brainSession;

    // 消息结果类型
    private ChatResultType resultType;

    public ChatResultType getResultType() {
        return resultType;
    }

    public void setResultType(ChatResultType resultType) {
        this.resultType = resultType;
    }

    public BrainSession getBrainSession() {
        return brainSession;
    }

    public void setBrainSession(BrainSession brainSession) {
        this.brainSession = brainSession;
    }
}
