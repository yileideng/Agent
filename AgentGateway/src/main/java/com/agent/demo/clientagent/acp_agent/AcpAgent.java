package com.agent.demo.clientagent.acp_agent;

import com.agent.demo.clientagent.core.AgentRequest;
import com.agent.demo.clientagent.core.AgentResponse;
import com.agent.demo.clientagent.core.AgentStreamSink;

import java.util.List;

/**
 * 网关内部的统一 Agent 运行时接口。
 *
 * <p>它描述“网关怎么调用一个 Agent”，不是 ACP 线协议。ACP Agent 由
 * {@code acp.AcpClientAgent} 适配成这个接口；内部 Agent（Self/Coze/CLI）由
 * 对应的 {@code AgentAdaptor} 实现直接接入。
 *
 * <p>实现放在同包下的 {@code impl} 或 {@code acp} 子包中：
 * - {@code impl.SelfAgentAdaptor}：自研 Agent 服务（HTTP）
 * - {@code impl.CozeAgentAdaptor}：扣子托管智能体
 * - {@code impl.CliAgentAdaptor}：CLI Agent（Qwen Code stream-json）
 * - {@code acp.AcpClientAgent}：原生/外部 ACP Agent（通用）
 *
 * <p>新增一个 Agent 只需实现本接口并声明为 Spring Bean；客户端和工具链路不变。
 */
public interface AcpAgent {

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

    /**
     * 释放 Agent 资源（子进程/会话）；内置 Bean 由 Spring 生命周期管理，
     * 外部注册的 Agent 在注销时由 Registry 调用。
     */
    default void close() {
    }
}
