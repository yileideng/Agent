package com.agent.demo.clientagent.acp_agent;

import com.agent.demo.clientagent.core.AgentRequest;
import com.agent.demo.clientagent.core.AgentResponse;
import com.agent.demo.clientagent.core.AgentStreamSink;
import com.agent.demo.clientagent.core.conversation.ConversationCoordinator;
import org.springframework.stereotype.Component;

/**
 * 网关的 Agent 路由层。
 *
 * <p>负责"按请求选择的 agent 名称找到可用 Agent"，把注册表细节和 Controller
 * 解耦。后续鉴权/配额策略也应在这里或更前面的 Authorizer 中执行。
 *
 * <p>聊天请求统一交给 {@link ConversationCoordinator}：切换 Agent 时复用同一个
 * 公共会话，由协调器补齐该 Agent 缺失的历史，而不是让每个 Agent 各记一份对话。
 */
@Component
public class AgentRouter {

    private final AcpAgentRegistry registry;
    private final ConversationCoordinator coordinator;

    public AgentRouter(AcpAgentRegistry registry, ConversationCoordinator coordinator) {
        this.registry = registry;
        this.coordinator = coordinator;
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

    /** 规范化公共会话 ID；同一会话切换 Agent 时保持不变。 */
    public String canonicalSessionId(String clientId, String endUserId, String sessionId) {
        return coordinator.canonicalSessionId(clientId, endUserId, sessionId);
    }

    public AgentResponse chat(AgentRequest request, String agentId) {
        return coordinator.chat(request, agentId);
    }

    public void stream(AgentRequest request, String agentId, AgentStreamSink sink) {
        coordinator.stream(request, agentId, sink);
    }
}
