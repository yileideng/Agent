package com.agent.demo.agentsdk;

/**
 * 一轮流式对话的最终结果。
 *
 * @param sessionId 下一轮续聊凭证
 * @param chatId    本轮对话 ID
 * @param agent     实际使用的 Agent
 * @param output    完整回答
 */
public record ChatStreamResult(String sessionId, String chatId, String agent, String output) {
}
