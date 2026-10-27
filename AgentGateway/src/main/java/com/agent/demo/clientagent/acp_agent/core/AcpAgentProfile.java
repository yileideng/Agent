package com.agent.demo.clientagent.acp_agent.core;

import java.util.List;

/**
 * 一个 ACP Agent 的启动配置。
 *
 * <p>它只描述“怎么启动/连接 Agent”，不包含会话和进程；进程由
 * {@link AcpConnection} 按需创建。
 *
 * @param agentId      网关内唯一名称，例如 qwen-acp
 * @param command      CLI 命令，例如 qwen 或 qwen.cmd
 * @param nodePath     可选，前置到子进程 PATH
 * @param authType     可选，Qwen Code 的认证方式
 * @param apiKey       可选，认证 key；不要硬编码到仓库
 * @param baseUrl      可选，OpenAI 兼容端点
 * @param model        可选，模型名
 * @param mcpUrl       网关 MCP 基地址，例如 http://localhost:8080/mcp
 * @param mcpServerName 写入 ACP mcpServers 的名称
 * @param workDir      每会话工作目录根路径；为空用系统临时目录
 * @param approvalMode 可选，Qwen 的 --approval-mode
 * @param timeoutSeconds 单轮 prompt 超时
 * @param useMcpConfigFile 是否用 --mcp-config 文件注入 MCP（Qwen ACP 的兼容开关）
 * @param args         启动参数；Qwen 传 ["--acp"]
 * @param wsUrl        外部 ACP Server 地址；非空时使用 WsAcpConnection
 * @param authToken    可选，WS Authorization: Bearer token
 */
public record AcpAgentProfile(
        String agentId,
        String command,
        String nodePath,
        String authType,
        String apiKey,
        String baseUrl,
        String model,
        String mcpUrl,
        String mcpServerName,
        String workDir,
        String approvalMode,
        long timeoutSeconds,
        boolean useMcpConfigFile,
        List<String> args,
        String wsUrl,
        String authToken) {
}
