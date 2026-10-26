package com.agent.demo.clientagent.acp_agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AcpAgent 注册表：收集容器内所有 {@link AcpAgent} 实现，按名称提供给网关选择。
 *
 * <p>当前以 Spring Bean 注册为主；外部 ACP Agent 的 descriptor 注册在后续阶段加入。
 */
@Component
public class AcpAgentRegistry {

    /** Agent 名称 -> 实现。 */
    private final Map<String, AcpAgent> agents;
    /** 请求未指定 agent 时使用的默认 Agent 名称。 */
    private final String defaultAgent;

    public AcpAgentRegistry(List<AcpAgent> agentList,
                            @Value("${agent.default-adaptor:mock}") String defaultAgent) {
        this.agents = agentList.stream()
                .collect(Collectors.toMap(AcpAgent::name, Function.identity(),
                        (first, second) -> first, LinkedHashMap::new));
        this.defaultAgent = defaultAgent;
    }

    /**
     * 按名称取 Agent，名称为空时使用默认 Agent；未知名称直接报错并列出可选项。
     */
    public AcpAgent get(String name) {
        String key = (name == null || name.isEmpty()) ? defaultAgent : name;
        AcpAgent agent = agents.get(key);
        if (agent == null) {
            throw new IllegalArgumentException("未知的agent: " + key + "，可选值: " + agents.keySet());
        }
        return agent;
    }

    /**
     * 返回所有已注册的 Agent，供 /agents 能力发现接口使用。
     */
    public List<AcpAgent> all() {
        return List.copyOf(agents.values());
    }

    /**
     * 默认 Agent 名称；请求未显式指定 agent 时使用。
     */
    public String defaultAdaptor() {
        return defaultAgent;
    }
}
