package com.agent.demo.clientagent.agent_adaptor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 适配器注册表：收集容器内所有AgentAdaptor实现，按名称提供给客户端选择。
 * 新增Agent只需新增一个Bean，无需改这里。
 */
@Component
public class AgentAdaptorRegistry {

    /** 适配器名称 -> 实现。 */
    private final Map<String, AgentAdaptor> adaptors;
    /** 请求未指定 agent 时使用的默认适配器名称。 */
    private final String defaultAdaptor;

    public AgentAdaptorRegistry(List<AgentAdaptor> adaptorList,
                                @Value("${agent.default-adaptor:mock}") String defaultAdaptor) {
        this.adaptors = adaptorList.stream()
                .collect(Collectors.toMap(AgentAdaptor::name, Function.identity(),
                        (first, second) -> first, LinkedHashMap::new));
        this.defaultAdaptor = defaultAdaptor;
    }

    /**
     * 按名称取适配器，名称为空时使用默认适配器；未知名称直接报错并列出可选项
     */
    public AgentAdaptor get(String name) {
        String key = (name == null || name.isEmpty()) ? defaultAdaptor : name;
        AgentAdaptor adaptor = adaptors.get(key);
        if (adaptor == null) {
            throw new IllegalArgumentException("未知的agent: " + key + "，可选值: " + adaptors.keySet());
        }
        return adaptor;
    }

    /**
     * 返回所有已注册的适配器，供 /agents 能力发现接口使用。
     */
    public List<AgentAdaptor> all() {
        return List.copyOf(adaptors.values());
    }

    /**
     * 默认适配器名称；请求未显式指定 agent 时使用。
     */
    public String defaultAdaptor() {
        return defaultAdaptor;
    }
}
