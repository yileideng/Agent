package com.agent.demo.agentbrain.api;

/**
 * 中间件调用自研Agent的请求。
 * 会话历史由中间件持有，这里只传本次决策需要的上下文。
 *
 * @param clientId     发起调用的客户端（租户）
 * @param endUserId    终端用户标识（多用户隔离）
 * @param sessionId    中间件持有的会话ID
 * @param newSession   是否本轮为新会话
 * @param userPrompt   用户输入，或客户端工具的执行结果
 * @param clientPrompt 客户端定制的系统提示词
 */
public record BrainChatRequest(String clientId,
                               String endUserId,
                               String sessionId,
                               boolean newSession,
                               String userPrompt,
                               String clientPrompt) {
}
