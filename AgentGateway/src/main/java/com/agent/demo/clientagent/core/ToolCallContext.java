package com.agent.demo.clientagent.core;

import java.util.UUID;

/**
 * 一次客户端工具调用的上下文。
 *
 * <p>chatId 标识"哪一轮对话"，callId 标识"哪一次工具调用"；
 * 多个工具调用可以共享同一个 chatId，但 callId 必须唯一。
 *
 * @param clientId  客户端（租户）
 * @param endUserId 终端用户
 * @param sessionId 会话
 * @param chatId    本轮对话
 * @param callId    本次工具调用（同时作为幂等键）
 * @param deadlineMs 等待客户端结果的超时时间
 */
public record ToolCallContext(String clientId,
                              String endUserId,
                              String sessionId,
                              String chatId,
                              String callId,
                              long deadlineMs) {

    public ToolCallContext {
        if (callId == null || callId.isBlank()) {
            callId = UUID.randomUUID().toString();
        }
        if (deadlineMs <= 0) {
            deadlineMs = 60_000;
        }
    }
}
