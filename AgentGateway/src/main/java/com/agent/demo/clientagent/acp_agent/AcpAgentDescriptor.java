package com.agent.demo.clientagent.acp_agent;

import java.util.List;

/**
 * 外部 ACP Agent 的注册描述。
 *
 * <p>它只描述“怎么启动/连接”，不包含进程和会话；真正执行时由
 * {@link AcpAgentFactory} 创建 {@code AcpClientAgent}。
 *
 * @param agentId          网关内唯一名称
 * @param transport        传输方式；当前只支持 STDIO
 * @param command          ACP Agent 启动命令
 * @param args             启动参数；为空表示命令本身不需要额外参数
 * @param nodePath         可选，前置到子进程 PATH
 * @param authType         可选，Agent 的认证方式
 * @param apiKey           可选，认证 key；只写入内存，不在查询接口返回
 * @param baseUrl          可选，OpenAI 兼容端点
 * @param model            可选，模型名
 * @param mcpUrl           网关 MCP 基地址
 * @param mcpServerName    MCP server 名称
 * @param workDir          每会话工作目录根路径
 * @param approvalMode     可选，CLI 审批模式
 * @param timeoutSeconds   单轮 prompt 超时
 * @param useMcpConfigFile 是否用 --mcp-config 注入 MCP
 */
public record AcpAgentDescriptor(
        String agentId,
        Transport transport,
        String command,
        List<String> args,
        String nodePath,
        String authType,
        String apiKey,
        String baseUrl,
        String model,
        String mcpUrl,
        String mcpServerName,
        String workDir,
        String approvalMode,
        Long timeoutSeconds,
        Boolean useMcpConfigFile) {

    public enum Transport {
        /** 网关启动本地 ACP Agent 子进程。 */
        STDIO,
        /** 远程 ACP Server；当前 AcpClientAgent 还没有 WS 传输实现。 */
        WS
    }
}
