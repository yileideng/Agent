package com.agent.demo.clientagent.acp_agent;

import com.agent.demo.clientagent.core.AgentRequest;
import com.agent.demo.clientagent.core.AgentResponse;
import com.agent.demo.clientagent.core.AgentStreamSink;
import org.springframework.stereotype.Component;

/**
 * 网关的 Agent 路由层。
 *
 * <p>负责“按请求选择的 agent 名称找到可用 Agent”，把注册表细节和 Controller
 * 解耦。后续鉴权/配额策略也应在这里或更前面的 Authorizer 中执行。
 */
@Component
public class AgentRouter {

    private final AcpAgentRegistry registry;

    public AgentRouter(AcpAgentRegistry registry) {
        this.registry = registry;
    }

    /**
     * 解析并校验 Agent 可用性；未知或不可用直接抛异常。
     */
    public AcpAgent resolve(String agentId) {
        AcpAgent agent = registry.get(agentId);
        if (!agent.available()) {
            throw new AgentUnavailableException(agent.name(), agent.unavailableReason());
        }
        return agent;
    }

    public AgentResponse chat(AgentRequest request, String agentId) {
        return resolve(agentId).chat(request);
    }

    public void stream(AgentRequest request, String agentId, AgentStreamSink sink) {
        resolve(agentId).chatStream(request, sink);
    }
}
