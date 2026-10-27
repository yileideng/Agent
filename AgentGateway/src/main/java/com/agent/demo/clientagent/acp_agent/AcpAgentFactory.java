package com.agent.demo.clientagent.acp_agent;

import com.agent.demo.clientagent.acp_agent.core.AcpAgentProfile;
import com.agent.demo.clientagent.acp_agent.impl.AcpClientAgent;
import com.agent.demo.clientagent.core.AgentSessionStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把外部注册的 {@link AcpAgentDescriptor} 转换成可调用的 {@link AcpAgent}。
 *
 * <p>外部 Agent 只通过 WebSocket 连接，不在 Gateway 本地启动进程。
 */
@Component
public class AcpAgentFactory {

    private final AgentSessionStore sessionStore;

    public AcpAgentFactory(AgentSessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    public AcpAgent create(AcpAgentDescriptor descriptor) {
        validate(descriptor);
        AcpAgentProfile profile = new AcpAgentProfile(
                descriptor.agentId(),
                null,
                null,
                null,
                null,
                null,
                null,
                blankToNull(descriptor.mcpUrl()),
                "client-tools",
                null,
                null,
                180,
                false,
                List.of(),
                descriptor.wsUrl(),
                descriptor.authToken());
        return new AcpClientAgent(profile, sessionStore);
    }

    private void validate(AcpAgentDescriptor descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor 不能为空");
        }
        if (descriptor.agentId() == null || !descriptor.agentId().matches("[a-zA-Z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("agentId 只能是 1-64 位字母、数字、点、下划线、短横线");
        }
        if (descriptor.wsUrl() == null || !descriptor.wsUrl().matches("^wss?://.+")) {
            throw new IllegalArgumentException("wsUrl 必须是 ws:// 或 wss:// 地址");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
