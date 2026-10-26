package com.agent.demo.clientagent.core;

/**
 * 适配器返回给客户端的统一结果。
 *
 * <p>工具调用由适配器内部驱动，不通过该对象返回；这里只承载会话 ID 和最终输出。
 *
 * @param sessionId 本轮使用的会话 ID，客户端下一轮续聊时回传
 * @param output    Agent 最终回答
 */
public record AgentResponse(String sessionId, String output) {
}
