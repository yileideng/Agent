package com.agent.demo.clientagent.controller.agent;

import com.agent.demo.clientagent.acp_agent.AcpAgent;
import com.agent.demo.clientagent.acp_agent.AcpAgentDescriptor;
import com.agent.demo.clientagent.acp_agent.AcpAgentRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 外部 ACP Agent 注册管理接口。
 *
 * 外部 Agent 由外部服务提供，注册只包含 WS 地址；Gateway 不在本地启动进程。
 */
@RestController
@RequestMapping("/admin/agents")
public class AgentRegistryController {

    private final AcpAgentRegistry registry;

    public AgentRegistryController(AcpAgentRegistry registry) {
        this.registry = registry;
    }

    /**
     * 注册一个外部 ACP Agent。
     *
     * <p>请求示例：
     * <pre>{@code
     * {
     *   "agentId": "remote-qwen",
     *   "wsUrl": "wss://agent.example.com/acp",
     *   "authToken": "optional-bearer-token",
     *   "mcpUrl": "https://gateway.example.com/mcp"
     * }
     * }</pre>
     */
    @PostMapping
    public ResponseEntity<?> register(@RequestBody AcpAgentDescriptor descriptor) {
        try {
            AcpAgent agent = registry.register(descriptor);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(view(agent, "EXTERNAL"));
        } catch (IllegalArgumentException e) {
            HttpStatus status = e.getMessage() != null && e.getMessage().contains("已存在")
                    ? HttpStatus.CONFLICT
                    : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(status)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 管理面列表：包含内置 Agent 和外部注册 Agent。
     */
    @GetMapping
    public List<AgentAdminView> list() {
        List<AgentAdminView> result = new ArrayList<>();
        for (AcpAgent agent : registry.all()) {
            String source = registry.isInternal(agent.name()) ? "INTERNAL" : "EXTERNAL";
            result.add(view(agent, source));
        }
        return result;
    }

    /**
     * 注销外部 Agent；内置 Agent 不允许注销。
     */
    @DeleteMapping("/{agentId}")
    public ResponseEntity<?> unregister(@PathVariable String agentId) {
        try {
            Optional<AcpAgent> removed = registry.unregister(agentId);
            if (removed.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Agent 不存在: " + agentId));
            }
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    private AgentAdminView view(AcpAgent agent, String source) {
        return new AgentAdminView(
                agent.name(),
                agent.description(),
                agent.available(),
                agent.unavailableReason(),
                source);
    }

    /**
     * 管理面视图：不返回 apiKey。
     */
    public record AgentAdminView(
            String agentId,
            String description,
            boolean available,
            String reason,
            String source) {
    }
}
