package com.agent.demo.agentsdk;

import java.util.Map;

/**
 * 客户端工具执行函数。
 *
 * <p>客户端开发者只需要实现这个接口，SDK 负责收消息、关联 callId、并发执行和回传结果。
 */
@FunctionalInterface
public interface ToolHandler {

    /**
     * @param arguments 工具参数（JSON 对象转换后的 Map）
     * @return 工具结果；会作为 tool_result.result 回传给网关
     */
    Map<String, Object> execute(Map<String, Object> arguments) throws Exception;
}
