package com.agent.demo.clientagent.agent_adaptor.impl;

import com.agent.demo.clientagent.agent_adaptor.AgentAdaptor;
import com.agent.demo.clientagent.core.AgentRequest;
import com.agent.demo.clientagent.core.AgentSessionStore;
import com.agent.demo.clientagent.core.AgentSession;
import com.agent.demo.clientagent.core.AgentResponse;
import com.agent.demo.clientagent.core.AgentResponseType;
import com.agent.demo.clientagent.core.Message;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * CLI Agent适配器：把 coding CLI(Qwen Code / Claude Code)的headless模式封装为Agent。
 * agent.cli.command指定CLI命令(默认qwen，换claude即可复用)。
 *
 * 原理：每条消息spawn一次 `qwen -p <prompt> --output-format stream-json --resume <cliSessionId>`，
 * 解析stdout的stream-json事件流；中间件按 (clientId, sessionId) 持有会话，并映射到CLI自己的sessionId。
 *
 * 已做的隔离/健壮性处理：
 * - 每会话独立工作目录：workspace/{clientId}/{sessionId}
 * - MCP 地址带 clientId，CLI 只能看到当前客户端的工具
 * - 进程超时强杀 + 读取线程回收
 * - mcp 配置文件每会话唯一，进程结束即删除
 *
 * todo: CLI的内置工具(读写文件/shell)仍在宿主执行，生产环境需容器沙箱隔离。
 */
@Component
public class CliAgentAdaptor implements AgentAdaptor {

    private final String command;
    private final String nodePath;
    // 非交互模式下的显式鉴权参数，留空则沿用CLI本地已登录的凭证
    private final String authType;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    // 外部客户端工具的MCP服务地址(基地址)，实际使用时会拼接 /{clientId}
    private final String mcpUrl;
    private final String mcpServerName;
    private final long timeoutSeconds;
    private final String workDir;
    private final AgentSessionStore sessionStore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CliAgentAdaptor(@Value("${agent.cli.command:qwen}") String command,
                           @Value("${agent.cli.node-path:}") String nodePath,
                           @Value("${agent.cli.auth-type:}") String authType,
                           @Value("${agent.cli.api-key:}") String apiKey,
                           @Value("${agent.cli.base-url:}") String baseUrl,
                           @Value("${agent.cli.model:}") String model,
                           @Value("${agent.cli.mcp-url:}") String mcpUrl,
                           @Value("${agent.cli.mcp-server-name:client-tools}") String mcpServerName,
                           @Value("${agent.cli.timeout-seconds:180}") long timeoutSeconds,
                           @Value("${agent.cli.work-dir:}") String workDir,
                           AgentSessionStore sessionStore) {
        this.command = command;
        this.nodePath = nodePath;
        this.authType = authType;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.mcpUrl = mcpUrl;
        this.mcpServerName = mcpServerName;
        this.timeoutSeconds = timeoutSeconds;
        this.workDir = workDir;
        this.sessionStore = sessionStore;
    }

    @Override
    public String name() {
        return "cli";
    }

    @Override
    public String description() {
        return "CLI Agent(Qwen Code / Claude Code)，通过命令行复用成熟Agent能力";
    }

    @Override
    public List<String> capabilities() {
        return List.of("chat", "client-tool-call", "mcp");
    }

    @Override
    public boolean available() {
        return command != null && !command.isBlank();
    }

    @Override
    public String unavailableReason() {
        return available() ? null : "未配置 agent.cli.command";
    }

    @Override
    public AgentResponse chat(AgentRequest request) {
        String clientId = request.clientId();
        String localSessionId = sessionStore.resolveSessionId(clientId, request.sessionId());
        AgentSessionStore.SessionState state = sessionStore.getOrCreate(name(), clientId, localSessionId);
        AgentSession agentSession = state.brainSession();
        agentSession.addMessage(new Message("user", request.userPrompt()));

        // MCP 地址按客户端隔离：CLI 只会看到该客户端注册的工具
        String clientMcpUrl = mcpUrlFor(clientId);
        Path mcpConfig = null;
        if (clientMcpUrl != null) {
            mcpConfig = writeMcpConfig(clientMcpUrl);
        }

        List<String> cmd = buildCommand(request, state);
        if (mcpConfig != null) {
            cmd.add("--mcp-config");
            cmd.add(mcpConfig.toString());
        }

        StringBuilder answer = new StringBuilder();
        StringBuilder errBuf = new StringBuilder();
        int exit;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(cmd).redirectErrorStream(true);
            // 指定node目录时前置到子进程PATH，解决CLI shim依赖PATH里的node版本问题
            if (!nodePath.isEmpty()) {
                processBuilder.environment().put("PATH", nodePath + ";" + processBuilder.environment().get("PATH"));
            }
            // 每会话独立工作目录，避免不同客户端/会话共享文件
            Path sessionDir = prepareWorkDir(clientId, localSessionId);
            processBuilder.directory(sessionDir.toFile());

            System.out.println("[Adaptor][cli][client=" + clientId + ",session=" + localSessionId
                    + ",cwd=" + sessionDir + "] 启动CLI进程: " + String.join(" ", cmd));
            Process process = processBuilder.start();
            Thread reader = new Thread(
                    () -> readStream(process, state, answer, errBuf), "cli-reader-" + safeName(localSessionId));
            reader.setDaemon(true);
            reader.start();

            boolean finished;
            try {
                finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待 " + command + " CLI 被中断", e);
            }
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                reader.join(2000);
                throw new IllegalStateException(command + " 执行超时(" + timeoutSeconds + "s)，已强制终止");
            }
            // 进程结束后读取线程应立即结束；留一点余量处理尾部输出
            reader.join(5000);
            if (reader.isAlive()) {
                System.out.println("[Adaptor][cli] 读取线程未在预期时间内结束，已忽略");
            }
            exit = process.exitValue();
        } catch (IOException e) {
            throw new IllegalStateException("调用 " + command + " CLI 失败，请确认已安装并在PATH中", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用 " + command + " CLI 被中断", e);
        } finally {
            if (mcpConfig != null) {
                try {
                    Files.deleteIfExists(mcpConfig);
                } catch (IOException e) {
                    System.out.println("[Adaptor][cli] 删除临时mcp配置失败: " + mcpConfig + ", " + e.getMessage());
                }
            }
        }
        if (exit != 0 || answer.isEmpty()) {
            throw new IllegalStateException(command + " 执行失败(exit=" + exit + "): " + errBuf);
        }

        agentSession.addMessage(new Message("assistant", answer.toString()));
        AgentResponse result = new AgentResponse();
        result.setBrainSession(agentSession);
        result.setResultType(AgentResponseType.FINAL_OUTPUT);
        result.setOutput(answer.toString());
        return result;
    }

    private List<String> buildCommand(AgentRequest request, AgentSessionStore.SessionState state) {
        // Windows下npm全局安装的CLI是.cmd shim，需经cmd执行
        List<String> cmd = new ArrayList<>();
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            cmd.add("cmd");
            cmd.add("/c");
        }
        cmd.add(command);
        cmd.add("-p");
        cmd.add(request.userPrompt());
        cmd.add("--output-format");
        cmd.add("stream-json");
        // 中间件持有的CLI会话引用，存在时续接上下文
        String cliSessionId = state.externalSessionId();
        if (cliSessionId != null) {
            cmd.add("--resume");
            cmd.add(cliSessionId);
        }
        if (request.clientPrompt() != null && !request.clientPrompt().isEmpty()) {
            cmd.add("--append-system-prompt");
            cmd.add(request.clientPrompt());
        }
        // 显式鉴权：不配置时沿用CLI本地登录凭证，配置后非交互模式无需登录
        if (!authType.isEmpty()) {
            cmd.add("--auth-type");
            cmd.add(authType);
        }
        if (!apiKey.isEmpty()) {
            cmd.add("--openai-api-key");
            cmd.add(apiKey);
        }
        if (!baseUrl.isEmpty()) {
            cmd.add("--openai-base-url");
            cmd.add(baseUrl);
        }
        if (!model.isEmpty()) {
            cmd.add("--model");
            cmd.add(model);
        }
        return cmd;
    }

    /**
     * 把 CLI 的 stream-json 输出解析成最终回答；遇到 system 事件时记录CLI会话ID
     */
    private void readStream(Process process, AgentSessionStore.SessionState state,
                            StringBuilder answer, StringBuilder errBuf) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                if (!line.startsWith("{")) {
                    errBuf.append(line).append('\n');
                    continue;
                }
                JsonNode node;
                try {
                    node = objectMapper.readTree(line);
                } catch (Exception e) {
                    errBuf.append(line).append('\n');
                    continue;
                }
                switch (node.path("type").asText("")) {
                    case "system" -> {
                        String sid = node.path("session_id").asText(null);
                        if (sid != null) {
                            state.bindExternalSessionId(sid);
                        }
                    }
                    case "assistant" -> {
                        JsonNode content = node.path("message").path("content");
                        if (content.isArray()) {
                            for (JsonNode block : content) {
                                if ("text".equals(block.path("type").asText())) {
                                    answer.append(block.path("text").asText());
                                }
                            }
                        }
                    }
                    case "result" -> {
                        if (node.path("is_error").asBoolean()) {
                            errBuf.append(node.path("error").path("message").asText("unknown error"));
                        } else if (answer.isEmpty()) {
                            answer.append(node.path("result").asText(""));
                        }
                    }
                    default -> { }
                }
            }
        } catch (IOException e) {
            errBuf.append("读取CLI输出失败: ").append(e.getMessage());
        }
    }

    private String mcpUrlFor(String clientId) {
        if (mcpUrl == null || mcpUrl.isBlank()) {
            return null;
        }
        String base = mcpUrl.endsWith("/") ? mcpUrl.substring(0, mcpUrl.length() - 1) : mcpUrl;
        return base + "/" + URLEncoder.encode(clientId, StandardCharsets.UTF_8);
    }

    private Path writeMcpConfig(String clientMcpUrl) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode server = root.putObject("mcpServers").putObject(mcpServerName);
        // qwen/gemini 系CLI用 httpUrl 表示 streamable HTTP 传输；trust 让headless模式无需人工确认工具调用
        server.put("httpUrl", clientMcpUrl);
        server.put("trust", true);
        server.put("timeout", 60000);
        try {
            Path file = Files.createTempFile("client-agent-mcp-", ".json");
            Files.writeString(file, objectMapper.writeValueAsString(root), StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("写入mcp-config文件失败", e);
        }
    }

    /**
     * 每会话独立工作目录，跨轮次复用同一目录以保留CLI侧的工作文件
     */
    private Path prepareWorkDir(String clientId, String localSessionId) {
        Path base = workDir == null || workDir.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "client-agent-workspaces")
                : Path.of(workDir);
        Path dir = base.resolve(safeName(clientId)).resolve(safeName(localSessionId));
        try {
            Files.createDirectories(dir);
            return dir;
        } catch (IOException e) {
            throw new IllegalStateException("创建CLI工作目录失败: " + dir, e);
        }
    }

    private String safeName(String value) {
        String safe = value == null ? "unknown" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
        return safe.length() > 80 ? safe.substring(0, 80) : safe;
    }
}
