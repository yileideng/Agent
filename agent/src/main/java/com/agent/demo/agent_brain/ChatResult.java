package com.agent.demo.agent_brain;

import java.util.Map;

public class ChatResult {
    // 包括Brain和Client的共享变量、消息列表等等
    private BrainSession brainSession;

    // 消息结果类型
    private ChatResultType resultType;

    // CLIENT_TOOL_CALL 时，Brain 请求调用的客户端工具名
    private String toolName;

    // CLIENT_TOOL_CALL 时，工具调用参数
    private Map<String, String> toolParameters;

    // FINAL_OUTPUT 时，Agent 的最终输出
    private String output;

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

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public Map<String, String> getToolParameters() {
        return toolParameters;
    }

    public void setToolParameters(Map<String, String> toolParameters) {
        this.toolParameters = toolParameters;
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String output) {
        this.output = output;
    }
}
