package com.agent.demo.clientagent.client_registry;

import java.time.Instant;
import java.util.List;

/**
 * 客户端注册信息：把"客户端"提升为一级注册实体。
 * 客户端持有传输方式、回调基地址、定制提示词，以及它提供的工具列表。
 *
 * <p>回调地址解析规则：工具的完整回调地址 = {@code baseCallbackUrl + (tool.callbackPath ?: defaultCallbackPath)}。
 *
 * @param clientId            客户端唯一标识，也是后续多租户隔离的边界
 * @param clientType          客户端类型：SERVER(HTTP回调) / MOBILE(WS推送)
 * @param baseCallbackUrl     回调基地址，SERVER 必填，MOBILE 为空
 * @param defaultCallbackPath 默认回调路径，工具未指定 callbackPath 时使用
 * @param clientPrompt        注入给 Agent 的客户端定制系统提示词
 * @param tools               该客户端提供的工具列表
 * @param registeredAt        注册时间
 * @param status              客户端状态：ONLINE / OFFLINE（MOBILE 跟踪 WS 连接状态，SERVER 恒为 ONLINE）
 */
public record ClientRegistration(
        String clientId,
        ClientType clientType,
        String baseCallbackUrl,
        String defaultCallbackPath,
        String clientPrompt,
        List<ToolDefinition> tools,
        Instant registeredAt,
        String status) {

    /**
     * 解析指定工具的完整回调地址。
     * 优先使用工具级 callbackPath，缺省回退到客户端的 defaultCallbackPath。
     */
    public String resolveCallbackUrl(ToolDefinition tool) {
        String path = tool.callbackPath() != null && !tool.callbackPath().isBlank()
                ? tool.callbackPath()
                : defaultCallbackPath;
        String base = baseCallbackUrl == null ? "" : baseCallbackUrl;
        if (path == null || path.isEmpty()) {
            return base;
        }
        boolean baseEndsWithSlash = base.endsWith("/");
        boolean pathStartsWithSlash = path.startsWith("/");
        if (baseEndsWithSlash && pathStartsWithSlash) {
            return base + path.substring(1);
        }
        if (!baseEndsWithSlash && !pathStartsWithSlash && !base.isEmpty()) {
            return base + "/" + path;
        }
        return base + path;
    }
}
