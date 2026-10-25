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

    private final Map<String, AgentAdaptor> adaptors;
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

    public List<AgentAdaptor> all() {
        return List.copyOf(adaptors.values());
    }

    public String defaultAdaptor() {
        return defaultAdaptor;
    }
}
