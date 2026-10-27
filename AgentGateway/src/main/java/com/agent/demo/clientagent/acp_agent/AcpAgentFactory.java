package com.agent.demo.clientagent.acp_agent;

import com.agent.demo.clientagent.acp_agent.core.AcpAgentProfile;
import com.agent.demo.clientagent.acp_agent.impl.AcpClientAgent;
import com.agent.demo.clientagent.core.AgentSessionStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把外部注册的 {@link AcpAgentDescriptor} 转换成可调用的 {@link AcpAgent}。
 *
 * <p>当前只实现 STDIO；WS 远程传输等 AcpConnection 增加 WebSocket 后再开放。
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
                descriptor.command(),
                blankToNull(descriptor.nodePath()),
                blankToNull(descriptor.authType()),
                blankToNull(descriptor.apiKey()),
                blankToNull(descriptor.baseUrl()),
                blankToNull(descriptor.model()),
                defaultIfBlank(descriptor.mcpUrl(), "http://localhost:8080/mcp"),
                defaultIfBlank(descriptor.mcpServerName(), "client-tools"),
                blankToNull(descriptor.workDir()),
                blankToNull(descriptor.approvalMode()),
                descriptor.timeoutSeconds() != null && descriptor.timeoutSeconds() > 0
                        ? descriptor.timeoutSeconds() : 180,
                Boolean.TRUE.equals(descriptor.useMcpConfigFile()),
                descriptor.args() == null ? List.of() : List.copyOf(descriptor.args()));
        return new AcpClientAgent(profile, sessionStore);
    }

    private void validate(AcpAgentDescriptor descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor 不能为空");
        }
        if (descriptor.agentId() == null || !descriptor.agentId().matches("[a-zA-Z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("agentId 只能是 1-64 位字母、数字、点、下划线、短横线");
        }
        if (descriptor.transport() != AcpAgentDescriptor.Transport.STDIO) {
            throw new IllegalArgumentException("当前只支持 transport=STDIO；WS ACP Server 传输尚未实现");
        }
        if (descriptor.command() == null || descriptor.command().isBlank()) {
            throw new IllegalArgumentException("command 不能为空");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
