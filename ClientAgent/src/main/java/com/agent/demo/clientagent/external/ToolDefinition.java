package com.agent.demo.clientagent.external;

import java.util.Map;

/**
 * 外部客户端注册的工具定义（统一的工具模型）
 *
 * @param clientId    提供该工具的客户端
 * @param name        工具名
 * @param description 给模型看的工具描述
 * @param parameters  参数名 -> 类型
 * @param callbackUrl 执行回调地址
 */
public record ToolDefinition(String clientId,
                             String name,
                             String description,
                             Map<String, String> parameters,
                             String callbackUrl) {
}
