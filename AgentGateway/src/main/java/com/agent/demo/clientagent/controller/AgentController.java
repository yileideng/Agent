package com.agent.demo.clientagent.controller;

import com.agent.demo.clientagent.acp_agent.AcpAgentRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 能力发现接口：客户端接入前先查询平台上有哪些可用Agent及其能力。
 * 后续多租户可按 clientId 裁剪可见Agent（allowedAgents）。
 */
@RestController
public class AgentController {

    /** Agent 注册表。 */
    private final AcpAgentRegistry registry;

    public AgentController(AcpAgentRegistry registry) {
        this.registry = registry;
    }

    /**
     * GET /agents
     *
     * <p>作用：查询平台当前可用的 Agent，供客户端接入前做能力发现。
     *
     * <p>入参：无。
     *
     * <p>返回 200 JSON：
     * <ul>
     *   <li>{@code default}：默认适配器名，请求 /chat 未指定 agent 时使用；</li>
     *   <li>{@code agents[]}：每个 Agent 的
     *       {@code name}、{@code description}、{@code capabilities}、
     *       {@code available}；不可用时带 {@code unavailableReason}。</li>
     * </ul>
     */
    @GetMapping("/agents")
    public Map<String, Object> list() {
        List<Map<String, Object>> agents = registry.all().stream().map(adaptor -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("name", adaptor.name());
            view.put("description", adaptor.description());
            view.put("capabilities", adaptor.capabilities());
            view.put("available", adaptor.available());
            if (adaptor.unavailableReason() != null) {
                view.put("unavailableReason", adaptor.unavailableReason());
            }
            return view;
        }).toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("default", registry.defaultAdaptor());
        response.put("agents", agents);
        return response;
    }
}
