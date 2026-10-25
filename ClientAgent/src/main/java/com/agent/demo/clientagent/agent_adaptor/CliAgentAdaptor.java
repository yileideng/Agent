package com.agent.demo.clientagent.agent_adaptor;

import com.agent.demo.clientagent.agent_brain.BrainSession;
import com.agent.demo.clientagent.agent_brain.ChatResult;
import com.agent.demo.clientagent.agent_brain.ChatResultType;
import com.agent.demo.clientagent.agent_brain.Message;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CLI Agent适配器：把 coding CLI(Qwen Code / Claude Code)的headless模式封装为Agent
 * agent.cli.command指定CLI命令(默认qwen，换claude即可复用)。
 * 原理：每条消息spawn一次 `qwen -p <prompt> --output-format stream-json --resume <cliSessionId>`，
 * 解析stdout的stream-json事件流，本地sessionId映射到CLI自己的sessionId以保持多轮上下文。
 * todo: CLI的内置工具(读写文件/shell)在服务端执行有安全风险，生产环境需沙箱隔离；
 *       CLI请求客户端工具(MCP)的接入待实现
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 本地sessionId -> CLI sessionId 的映射，demo用内存Map，实际应落库
    private final Map<String, String> sessionMap = new ConcurrentHashMap<>();

    // todo: 接入CLI的客户端工具调用(如MCP)时，通过它路由回客户端
    private ClientToolExecutor clientToolExecutor;

    public CliAgentAdaptor(@Value("${agent.cli.command:qwen}") String command,
                           @Value("${agent.cli.node-path:}") String nodePath,
                           @Value("${agent.cli.auth-type:}") String authType,
                           @Value("${agent.cli.api-key:}") String apiKey,
                           @Value("${agent.cli.base-url:}") String baseUrl,
                           @Value("${agent.cli.model:}") String model) {
        this.command = command;
        this.nodePath = nodePath;
        this.authType = authType;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    @Override
    public String name() {
        return "cli";
    }

    @Override
    public void setClientToolExecutor(ClientToolExecutor clientToolExecutor) {
        this.clientToolExecutor = clientToolExecutor;
    }

    @Override
    public ChatResult chat(String userPrompt, String clientPrompt, String sessionId) {
        BrainSession brainSession = new BrainSession().initSession(sessionId);
        brainSession.addMessage(new Message("user", userPrompt));

        // 组装命令：Windows下npm全局安装的CLI是.cmd shim，需经cmd执行
        List<String> cmd = new ArrayList<>();
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            cmd.add("cmd");
            cmd.add("/c");
        }
        cmd.add(command);
        cmd.add("-p");
        cmd.add(userPrompt);
        cmd.add("--output-format");
        cmd.add("stream-json");
        String cliSessionId = sessionMap.get(brainSession.getSessionId());
        if (cliSessionId != null) {
            cmd.add("--resume");
            cmd.add(cliSessionId);
        }
        if (clientPrompt != null && !clientPrompt.isEmpty()) {
            cmd.add("--append-system-prompt");
            cmd.add(clientPrompt);
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

        // 执行并逐行解析stream-json；stderr合并进stdout，非JSON行按日志/错误收集
        StringBuilder answer = new StringBuilder();
        StringBuilder errBuf = new StringBuilder();
        int exit;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(cmd).redirectErrorStream(true);
            // 指定node目录时前置到子进程PATH，解决CLI shim依赖PATH里的node版本问题
            if (!nodePath.isEmpty()) {
                processBuilder.environment().put("PATH", nodePath + ";" + processBuilder.environment().get("PATH"));
            }
            System.out.println("[Adaptor] 启动CLI进程: " + String.join(" ", cmd));
            Process process = processBuilder.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    if (!line.startsWith("{")) {
                        errBuf.append(line).append('\n');
                        continue;
                    }
                    JsonNode node = objectMapper.readTree(line);
                    switch (node.path("type").asText("")) {
                        case "system" -> {
                            String sid = node.path("session_id").asText(null);
                            if (sid != null) {
                                sessionMap.put(brainSession.getSessionId(), sid);
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
            }
            exit = process.waitFor();
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用 " + command + " CLI 失败，请确认已安装并在PATH中", e);
        }
        if (exit != 0 || answer.isEmpty()) {
            throw new IllegalStateException(command + " 执行失败(exit=" + exit + "): " + errBuf);
        }

        brainSession.addMessage(new Message("assistant", answer.toString()));
        ChatResult result = new ChatResult();
        result.setBrainSession(brainSession);
        result.setResultType(ChatResultType.FINAL_OUTPUT);
        result.setOutput(answer.toString());
        return result;
    }
}
