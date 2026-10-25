package com.agent.demo.clientagent.controller;

import com.agent.demo.clientagent.agent_adaptor.AgentAdaptorRegistry;
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

    private final AgentAdaptorRegistry registry;

    public AgentController(AgentAdaptorRegistry registry) {
        this.registry = registry;
    }

    /**
     * GET /agents
     * 查询平台当前可用的 Agent 列表，供客户端接入前做能力发现。
     * 返回：default 为默认适配器名；agents 为每个 Agent 的名称、描述、能力标签、是否可用及不可用原因。
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
