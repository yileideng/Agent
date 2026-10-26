package com.agent.demo.agentclient.tool;

import java.util.Map;

/**
 * 客户端工具契约：客户端实现后由 Spring 自动收集并注册给中间件。
 */
public interface ClientTool {

    /** 工具名，同一客户端内唯一。 */
    String getName();

    /** 给模型看的用途描述：做什么、什么时候调用。 */
    String getDescription();

    /**
     * 参数的标准 JSON Schema，包含 type / description / required 等。
     * 无参数工具返回 {@code {"type":"object","additionalProperties":false}}。
     */
    Map<String, Object> getInputSchema();

    /** 在客户端本地执行工具；参数已按 inputSchema 解析为 JSON 值。 */
    Map<String, Object> execute(Map<String, Object> parameters);
}
