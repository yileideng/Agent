package com.agent.demo.clientagent.core;

import java.util.Map;

/**
 * 适配器返回给客户端的统一结果
 */
public class AgentResponse {

    // 包括Brain和Client的共享变量、消息列表等等
    private AgentSession agentSession;

    // 消息结果类型
    private AgentResponseType resultType;

    // CLIENT_TOOL_CALL 时，Agent 请求调用的客户端工具名
    private String toolName;

    // CLIENT_TOOL_CALL 时，工具调用参数
    private Map<String, String> toolParameters;

    // FINAL_OUTPUT 时，Agent 的最终输出
    private String output;

    public AgentResponseType getResultType() {
        return resultType;
    }

    public void setResultType(AgentResponseType resultType) {
        this.resultType = resultType;
    }

    public AgentSession getBrainSession() {
        return agentSession;
    }

    public void setBrainSession(AgentSession agentSession) {
        this.agentSession = agentSession;
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
