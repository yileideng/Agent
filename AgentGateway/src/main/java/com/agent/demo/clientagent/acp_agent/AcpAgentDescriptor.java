package com.agent.demo.clientagent.acp_agent;

/**
 * 外部 ACP Agent 的注册描述。
 *
 * <p>外部 Agent 由外部服务提供，Gateway 只保存 WS 地址和可选 token，
 * 不在本地启动进程。
 *
 * @param agentId   网关内唯一名称
 * @param wsUrl     ACP Server WebSocket 地址，例如 wss://agent.example.com/acp
 * @param authToken 可选，作为 Authorization: Bearer 发送
 * @param mcpUrl    可选，外部 Agent 可访问的 Gateway MCP 地址
 */
public record AcpAgentDescriptor(
        String agentId,
        String wsUrl,
        String authToken,
        String mcpUrl) {
}
