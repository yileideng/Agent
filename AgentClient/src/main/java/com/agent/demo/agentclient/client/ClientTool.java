package com.agent.demo.agentclient.client;

import java.util.Map;

/**
 * 客户端工具契约：客户端实现后由 Spring 自动收集并注册给中间件。
 */
public interface ClientTool {

    String getName();

    String getDescription();

    Map<String, String> getParameters();

    Map<String, String> getReturnParameters();

    Map<String, String> execute(Map<String, String> parameters);
}
