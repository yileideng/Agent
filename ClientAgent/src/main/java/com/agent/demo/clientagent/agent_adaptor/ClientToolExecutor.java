package com.agent.demo.clientagent.agent_adaptor;

import com.agent.demo.clientagent.agent_brain.ChatResult;

import java.util.Map;

/**
 * 客户端工具执行器：进程内 Adaptor 把 Agent 的工具调用请求路由到客户端执行
 */
public interface ClientToolExecutor {

    /**
     * 执行客户端工具，并把结果回调给发起调用的Agent继续Agent Loop
     * @param adaptor 发起工具调用的适配器，回调时继续用它
     * @param request 原调用请求，回调时沿用同一 clientId/sessionId
     */
    ChatResult execute(AgentAdaptor adaptor, String toolName, Map<String, String> parameters, AgentRequest request);
}
