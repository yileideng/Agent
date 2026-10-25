package com.agent.demo.clientagent.client_tool;

import java.util.Map;

/**
 * 外部客户端注册的工具定义（统一的工具模型）。
 *
 * <p>注意：工具不再携带 callbackUrl，回调基地址由所属客户端（{@link ClientRegistration}）统一提供，
 * 完整回调地址 = {@code client.baseCallbackUrl + (tool.callbackPath ?: client.defaultCallbackPath)}。
 *
 * @param name         工具名（同一 clientId 内唯一）
 * @param description  给模型看的工具描述
 * @param parameters   参数名 -> 类型
 * @param callbackPath 工具级回调路径（可选，缺省使用客户端的 defaultCallbackPath）
 */
public record ToolDefinition(
        String name,
        String description,
        Map<String, String> parameters,
        String callbackPath) {
}
