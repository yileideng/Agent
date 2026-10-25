package com.agent.demo.clientagent.agent_adaptor;

import com.agent.demo.clientagent.agent_brain.ChatResult;

import java.util.Map;

/**
 * 客户端工具执行器，由客户端实现。
 * AgentBrain 决定调用客户端工具时，AgentAdaptor 通过该接口把工具调用路由回客户端执行
 */
public interface ClientToolExecutor {
    ChatResult execute(String toolName, Map<String, String> parameters, String sessionId);
}
