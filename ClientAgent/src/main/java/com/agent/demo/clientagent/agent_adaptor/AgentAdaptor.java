package com.agent.demo.clientagent.agent_adaptor;

import com.agent.demo.clientagent.agent_brain.ChatResult;

/**
 * Agent适配器接口：不同Agent的接入点，实现可插拔替换
 * - MockAgentAdaptor：本地mock，验证客户端工具调用链
 * - CozeAgentAdaptor：对接扣子托管智能体
 * - CliAgentAdaptor：封装CLI Agent(Qwen Code / Claude Code)
 */
public interface AgentAdaptor {

    /**
     * 适配器名称，供调用方通过参数选择使用哪个Agent
     */
    String name();

    /**
     * 执行Agent对话，触发方是客户端
     * @param userPrompt 用户提示词或工具执行结果
     * @param clientPrompt 客户端定制的系统提示词
     * @param sessionId 对话的sessionID，为空表示新会话
     */
    ChatResult chat(String userPrompt, String clientPrompt, String sessionId);

    /**
     * 注册客户端工具执行器，Agent决定调用客户端工具时通过它路由回客户端执行
     */
    void setClientToolExecutor(ClientToolExecutor clientToolExecutor);
}
