package com.agent.demo.agentbrain.api;

import java.util.Map;

/**
 * 自研 Agent 的决策结果。
 *
 * @param type           决策类型：CLIENT_TOOL_CALL 或 FINAL_OUTPUT
 * @param toolName       CLIENT_TOOL_CALL 时要调用的客户端工具名
 * @param toolParameters CLIENT_TOOL_CALL 时的工具参数
 * @param output         FINAL_OUTPUT 时的最终回答
 */
public record BrainChatResponse(ActionType type,
                                String toolName,
                                Map<String, String> toolParameters,
                                String output) {

    public enum ActionType {
        /** 请求中间件调用客户端工具。 */
        CLIENT_TOOL_CALL,
        /** 本轮结束，返回最终回答。 */
        FINAL_OUTPUT
    }

    /**
     * 构造“调用客户端工具”的决策。
     */
    public static BrainChatResponse toolCall(String toolName, Map<String, String> parameters) {
        return new BrainChatResponse(ActionType.CLIENT_TOOL_CALL, toolName, parameters, null);
    }

    /**
     * 构造“最终输出”的决策。
     */
    public static BrainChatResponse finalOutput(String output) {
        return new BrainChatResponse(ActionType.FINAL_OUTPUT, null, null, output);
    }
}
