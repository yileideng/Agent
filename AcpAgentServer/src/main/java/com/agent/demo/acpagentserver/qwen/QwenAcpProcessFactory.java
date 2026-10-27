package com.agent.demo.acpagentserver.qwen;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 按配置启动本地 {@code qwen --acp} 进程。
 */
@Component
public class QwenAcpProcessFactory {

    private final String command;
    private final String nodePath;
    private final String authType;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final String approvalMode;

    public QwenAcpProcessFactory(
            @Value("${qwen.command:qwen}") String command,
            @Value("${qwen.node-path:}") String nodePath,
            @Value("${qwen.auth-type:}") String authType,
            @Value("${qwen.api-key:${AGENT_ACP_API_KEY:}}") String apiKey,
            @Value("${qwen.base-url:}") String baseUrl,
            @Value("${qwen.model:}") String model,
            @Value("${qwen.approval-mode:default}") String approvalMode) {
        this.command = command;
        this.nodePath = nodePath;
        this.authType = authType;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.approvalMode = approvalMode;
    }

    public QwenAcpProcess start(Consumer<String> onMessage, Consumer<String> onError) {
        List<String> cmd = new ArrayList<>();
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            cmd.add("cmd");
            cmd.add("/c");
        }
        cmd.add(command);
        cmd.add("--acp");
        if (approvalMode != null && !approvalMode.isBlank()) {
            cmd.add("--approval-mode");
            cmd.add(approvalMode);
        }
        if (authType != null && !authType.isBlank()) {
            cmd.add("--auth-type");
            cmd.add(authType);
        }
        if (apiKey != null && !apiKey.isBlank()) {
            cmd.add("--openai-api-key");
            cmd.add(apiKey);
        }
        if (baseUrl != null && !baseUrl.isBlank()) {
            cmd.add("--openai-base-url");
            cmd.add(baseUrl);
        }
        if (model != null && !model.isBlank()) {
            cmd.add("--model");
            cmd.add(model);
        }

        try {
            ProcessBuilder builder = new ProcessBuilder(cmd);
            builder.directory(Path.of(System.getProperty("java.io.tmpdir")).toFile());
            builder.redirectErrorStream(false);
            if (nodePath != null && !nodePath.isBlank()) {
                String path = builder.environment().get("PATH");
                builder.environment().put("PATH", nodePath + ";" + (path == null ? "" : path));
            }
            Process process = builder.start();
            return new QwenAcpProcess(process, onMessage, onError);
        } catch (IOException e) {
            throw new IllegalStateException("启动 Qwen ACP 失败: " + e.getMessage(), e);
        }
    }
}
