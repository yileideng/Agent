package com.agent.demo.clientagent.agent_adaptor;

import com.agent.demo.clientagent.agent_brain.ChatResult;

import java.util.List;

/**
 * Agent适配器接口：不同Agent的接入点，实现可插拔替换
 * - MockAgentAdaptor：本地mock，验证客户端工具调用链
 * - CozeAgentAdaptor：对接扣子托管智能体
 * - CliAgentAdaptor：封装CLI Agent(Qwen Code / Claude Code)
 *
 * 新增一个 Agent 只需实现本接口并声明为 Spring Bean，无需改动客户端与工具链路。
 */
public interface AgentAdaptor {

    /**
     * 适配器名称，供调用方通过参数选择使用哪个Agent
     */
    String name();

    /**
     * 能力描述，供客户端在 /agents 中展示与选择
     */
    default String description() {
        return name();
    }

    /**
     * 能力标签，例如 chat / client-tool-call / mcp / streaming
     */
    default List<String> capabilities() {
        return List.of("chat");
    }

    /**
     * 当前配置下是否可用（如扣子未配置 token 时不可用）
     */
    default boolean available() {
        return true;
    }

    /**
     * 不可用原因，available()=true 时返回 null
     */
    default String unavailableReason() {
        return null;
    }

    /**
     * 执行Agent对话，触发方是客户端。
     * 会话状态由适配器层通过 AgentSessionStore 统一持有，Brain 只负责一次决策。
     */
    ChatResult chat(AgentRequest request);

    /**
     * 注册客户端工具执行器：进程内 Agent 通过它路由工具调用。
     * 走 MCP 的 Agent(CLI) 不依赖该钩子，因此默认空实现。
     */
    default void setClientToolExecutor(ClientToolExecutor clientToolExecutor) {
    }
}
