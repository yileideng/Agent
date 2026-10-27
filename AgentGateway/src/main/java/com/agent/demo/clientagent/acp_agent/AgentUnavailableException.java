package com.agent.demo.clientagent.acp_agent;

/**
 * Agent 不可用：未知、未启用或缺少配置。
 */
public class AgentUnavailableException extends RuntimeException {

    public AgentUnavailableException(String agentId, String reason) {
        super("Agent 不可用: " + agentId + (reason == null || reason.isBlank() ? "" : "，原因: " + reason));
    }
}
