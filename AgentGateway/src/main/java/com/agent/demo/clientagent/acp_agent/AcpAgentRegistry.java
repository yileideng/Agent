package com.agent.demo.clientagent.acp_agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * AcpAgent 注册表：同时管理内置 Agent（Spring Bean）和外部注册的 ACP Agent。
 *
 * <p>内置 Agent 只读；外部 Agent 通过 {@link #register} 动态加入，通过
 * {@link #unregister} 移除。Registry 只保存描述和实例，不持有进程。
 */
@Component
public class AcpAgentRegistry {

    /** 内置 Agent：应用启动时由 Spring 注入，不允许外部注销。 */
    private final Map<String, AcpAgent> internal;
    /** 外部分 Agent：运行时注册。 */
    private final ConcurrentMap<String, AcpAgent> external = new ConcurrentHashMap<>();
    /** 外部 Agent 的原始描述，查询/管理接口使用，不返回 apiKey。 */
    private final ConcurrentMap<String, AcpAgentDescriptor> externalDescriptors = new ConcurrentHashMap<>();
    /** 请求未指定 agent 时使用的默认 Agent 名称。 */
    private final String defaultAgent;
    private final AcpAgentFactory factory;

    public AcpAgentRegistry(List<AcpAgent> agentList,
                            AcpAgentFactory factory,
                            @Value("${agent.default-adaptor:mock}") String defaultAgent) {
        this.factory = factory;
        this.internal = new LinkedHashMap<>();
        for (AcpAgent agent : agentList) {
            this.internal.put(agent.name(), agent);
        }
        this.defaultAgent = defaultAgent;
    }

    /**
     * 按名称取 Agent，名称为空时使用默认 Agent；未知名称直接报错并列出可选项。
     */
    public AcpAgent get(String name) {
        String key = (name == null || name.isEmpty()) ? defaultAgent : name;
        AcpAgent agent = internal.get(key);
        if (agent == null) {
            agent = external.get(key);
        }
        if (agent == null) {
            throw new IllegalArgumentException("未知的agent: " + key + "，可选值: " + names());
        }
        return agent;
    }

    /**
     * 注册一个外部 ACP Agent；agentId 必须全局唯一。
     */
    public synchronized AcpAgent register(AcpAgentDescriptor descriptor) {
        String agentId = descriptor == null ? null : descriptor.agentId();
        if (agentId != null && (internal.containsKey(agentId) || external.containsKey(agentId))) {
            throw new IllegalArgumentException("agentId 已存在: " + agentId);
        }
        AcpAgent agent = factory.create(descriptor);
        external.put(agent.name(), agent);
        externalDescriptors.put(agent.name(), descriptor);
        return agent;
    }

    /**
     * 注销外部 Agent；内置 Agent 不允许注销。
     *
     * @return 被移除的 Agent；不存在时为 empty
     */
    public synchronized Optional<AcpAgent> unregister(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return Optional.empty();
        }
        if (internal.containsKey(agentId)) {
            throw new IllegalArgumentException("内置 Agent 不能注销: " + agentId);
        }
        externalDescriptors.remove(agentId);
        AcpAgent removed = external.remove(agentId);
        if (removed != null) {
            removed.close();
        }
        return Optional.ofNullable(removed);
    }

    /**
     * 返回所有已注册的 Agent，供 /agents 能力发现接口使用。
     */
    public List<AcpAgent> all() {
        List<AcpAgent> result = new ArrayList<>(internal.values());
        result.addAll(external.values());
        return List.copyOf(result);
    }

    /**
     * 外部 Agent 描述；管理接口使用。
     */
    public List<AcpAgentDescriptor> externalDescriptors() {
        return List.copyOf(externalDescriptors.values());
    }

    public boolean isInternal(String agentId) {
        return internal.containsKey(agentId);
    }

    public List<String> names() {
        List<String> names = new ArrayList<>(internal.keySet());
        names.addAll(external.keySet());
        return List.copyOf(names);
    }

    /**
     * 默认 Agent 名称；请求未显式指定 agent 时使用。
     */
    public String defaultAdaptor() {
        return defaultAgent;
    }
}
