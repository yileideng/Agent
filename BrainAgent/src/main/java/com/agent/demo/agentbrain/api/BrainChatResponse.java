package com.agent.demo.agentbrain.api;

import java.util.Map;

/**
 * 自研Agent的决策结果。
 * CLIENT_TOOL_CALL：请求中间件调用客户端工具；FINAL_OUTPUT：本轮结束并返回最终回答。
 */
public record BrainChatResponse(ActionType type,
                                String toolName,
                                Map<String, String> toolParameters,
                                String output) {

    public enum ActionType {
        CLIENT_TOOL_CALL,
        FINAL_OUTPUT
    }

    public static BrainChatResponse toolCall(String toolName, Map<String, String> parameters) {
        return new BrainChatResponse(ActionType.CLIENT_TOOL_CALL, toolName, parameters, null);
    }

    public static BrainChatResponse finalOutput(String output) {
        return new BrainChatResponse(ActionType.FINAL_OUTPUT, null, null, output);
    }
}
