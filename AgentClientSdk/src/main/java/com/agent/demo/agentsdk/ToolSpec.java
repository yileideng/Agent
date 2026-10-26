package com.agent.demo.agentsdk;

import java.util.Map;

/**
 * SDK 内部使用的工具定义。
 *
 * @param name        工具名
 * @param description 给模型看的描述
 * @param inputSchema 标准 JSON Schema（type/description/required 等）
 * @param handler     客户端执行函数
 */
public record ToolSpec(String name,
                       String description,
                       Map<String, Object> inputSchema,
                       ToolHandler handler) {
}
