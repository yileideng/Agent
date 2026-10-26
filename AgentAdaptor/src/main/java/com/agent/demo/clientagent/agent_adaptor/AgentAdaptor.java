package com.agent.demo.clientagent.agent_adaptor;

import com.agent.demo.clientagent.core.AgentRequest;
import com.agent.demo.clientagent.core.AgentResponse;
import com.agent.demo.clientagent.core.AgentStreamSink;

import java.util.List;

/**
 * Agent适配器接口：不同Agent的接入点，实现可插拔替换。
 * 具体实现放在同包下的 {@code impl} 子包中：
 * - {@code impl.SelfAgentAdaptor}：调用自研Agent服务(AgentBrain)，通过HTTP
 * - {@code impl.CozeAgentAdaptor}：对接扣子托管智能体
 * - {@code impl.CliAgentAdaptor}：封装CLI Agent(Qwen Code / Claude Code)
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
     * 执行Agent对话（阻塞版），触发方是客户端。
     * 会话状态由适配器层通过 AgentSessionStore 统一持有，Brain 只负责一次决策。
     */
    AgentResponse chat(AgentRequest request);

    /**
     * 流式执行 Agent 对话。
     *
     * <p>默认实现退化为“一次性 final”，保证不实现流式的适配器仍可用。
     * 支持流式的适配器应覆盖本方法，把厂商事件逐条翻译成
     * {@link AgentStreamSink#delta}/{@link AgentStreamSink#thinking} 和
     * {@link AgentStreamSink#finish}，不能等整段回答收完再发。
     */
    default void chatStream(AgentRequest request, AgentStreamSink sink) {
        try {
            AgentResponse response = chat(request);
            sink.finish(response.sessionId(), response.output());
        } catch (Exception e) {
            sink.error("AGENT_ERROR", e.getMessage());
        }
    }
}
