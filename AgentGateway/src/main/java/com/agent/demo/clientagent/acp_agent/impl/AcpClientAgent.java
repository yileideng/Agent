package com.agent.demo.clientagent.acp_agent.impl;

import com.agent.demo.clientagent.acp_agent.AcpAgent;
import com.agent.demo.clientagent.acp_agent.core.AcpAgentProfile;
import com.agent.demo.clientagent.acp_agent.core.AcpSession;
import com.agent.demo.clientagent.core.AgentRequest;
import com.agent.demo.clientagent.core.AgentResponse;
import com.agent.demo.clientagent.core.AgentSessionStore;
import com.agent.demo.clientagent.core.AgentStreamSink;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通用 ACP Agent 接入器：启动 ACP Agent 子进程，把 ACP 会话映射成网关的 AcpAgent。
 *
 * <p>当前以 Qwen Code {@code qwen --acp} 为验证目标；后续 Claude/Codex 的 ACP
 * adapter 只需换 profile 的 command/args，不需要改这里。
 */
@Component
public class AcpClientAgent implements AcpAgent {

    private final AcpAgentProfile profile;
    private final AgentSessionStore sessionStore;
    private final boolean enabled;
    private final Map<String, AcpSession> sessions = new ConcurrentHashMap<>();

    @Autowired
    public AcpClientAgent(
            @Value("${agent.acp.qwen.enabled:false}") boolean enabled,
            @Value("${agent.acp.qwen.id:qwen-acp}") String agentId,
            @Value("${agent.acp.qwen.command:qwen}") String command,
            @Value("${agent.acp.qwen.node-path:}") String nodePath,
            @Value("${agent.acp.qwen.auth-type:}") String authType,
            @Value("${agent.acp.qwen.api-key:${AGENT_ACP_API_KEY:}}") String apiKey,
            @Value("${agent.acp.qwen.base-url:}") String baseUrl,
            @Value("${agent.acp.qwen.model:}") String model,
            @Value("${agent.acp.qwen.mcp-url:http://localhost:8080/mcp}") String mcpUrl,
            @Value("${agent.acp.qwen.mcp-server-name:client-tools}") String mcpServerName,
            @Value("${agent.acp.qwen.work-dir:}") String workDir,
            @Value("${agent.acp.qwen.approval-mode:default}") String approvalMode,
            @Value("${agent.acp.qwen.timeout-seconds:180}") long timeoutSeconds,
            @Value("${agent.acp.qwen.use-mcp-config-file:false}") boolean useMcpConfigFile,
            AgentSessionStore sessionStore) {
        this.enabled = enabled;
        this.sessionStore = sessionStore;
        this.profile = new AcpAgentProfile(
                agentId, command, nodePath, authType, apiKey, baseUrl, model,
                mcpUrl, mcpServerName, workDir, approvalMode, timeoutSeconds, useMcpConfigFile,
                List.of("--acp"), null, null);
    }

    /**
     * 外部 ACP Agent 使用：profile 由注册接口构造，不经过 Spring 配置。
     */
    public AcpClientAgent(AcpAgentProfile profile, AgentSessionStore sessionStore) {
        this.profile = profile;
        this.sessionStore = sessionStore;
        this.enabled = true;
    }

    @Override
    public String name() {
        return profile.agentId();
    }

    @Override
    public String description() {
        return profile.wsUrl() != null && !profile.wsUrl().isBlank()
                ? "ACP Agent（WebSocket，通用 AcpClientAgent）"
                : "ACP Agent（stdio JSON-RPC，通用 AcpClientAgent）";
    }

    @Override
    public List<String> capabilities() {
        return List.of("chat", "streaming", "thinking", "mcp", "acp");
    }

    @Override
    public boolean available() {
        return enabled && ((profile.command() != null && !profile.command().isBlank())
                || (profile.wsUrl() != null && !profile.wsUrl().isBlank()));
    }

    @Override
    public String unavailableReason() {
        if (!enabled) {
            return "agent 未启用";
        }
        if ((profile.command() == null || profile.command().isBlank())
                && (profile.wsUrl() == null || profile.wsUrl().isBlank())) {
            return "未配置 command 或 wsUrl";
        }
        return null;
    }

    @Override
    public AgentResponse chat(AgentRequest request) {
        AtomicReference<String> sessionId = new AtomicReference<>();
        AtomicReference<String> output = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        chatStream(request, new AgentStreamSink() {
            @Override
            public void delta(String text) {
                // chatStream 内部已累积，这里不需要重复
            }

            @Override
            public void finish(String sid, String out) {
                sessionId.set(sid);
                output.set(out);
            }

            @Override
            public void error(String code, String message) {
                error.set(code + ": " + message);
            }
        });
        if (error.get() != null) {
            throw new IllegalStateException(error.get());
        }
        return new AgentResponse(sessionId.get(), output.get() == null ? "" : output.get());
    }

    @Override
    public void chatStream(AgentRequest request, AgentStreamSink sink) {
        if (!available()) {
            sink.error("AGENT_UNAVAILABLE", unavailableReason());
            return;
        }
        String localSessionId = sessionStore.resolveSessionId(
                request.clientId(), request.endUserId(), request.sessionId());
        AcpSession session = sessions.computeIfAbsent(localSessionId,
                id -> new AcpSession(profile, id, request.clientId(), request.endUserId()));
        try {
            StringBuilder answer = new StringBuilder();
            String output = session.prompt(request, answer, sink);
            sink.finish(localSessionId, output);
        } catch (Exception e) {
            sink.error("ACP_ERROR", e.getMessage());
        }
    }

    @PreDestroy
    public void close() {
        sessions.values().forEach(AcpSession::close);
        sessions.clear();
    }
}
