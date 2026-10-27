package com.agent.demo.clientagent.acp_agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * ACP stdio 传输：启动 Agent 子进程，按行收发 JSON-RPC 2.0。
 *
 * <p>只负责传输和请求/响应关联；会话、事件、权限语义放在 {@link AcpSession}。
 * stdout 必须是纯 JSON-RPC，stderr 单独读取，不能合并到 stdout。
 */
public final class AcpConnection implements AutoCloseable {

    private final AcpAgentProfile profile;
    private final List<String> extraArgs;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong nextId = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile Process process;
    private volatile BufferedWriter writer;
    private volatile Consumer<JsonNode> notificationHandler = ignored -> {
    };
    private volatile Consumer<JsonNode> requestHandler = node -> {
    };

    public AcpConnection(AcpAgentProfile profile) {
        this(profile, List.of());
    }

    public AcpConnection(AcpAgentProfile profile, List<String> extraArgs) {
        this.profile = profile;
        this.extraArgs = extraArgs == null ? List.of() : List.copyOf(extraArgs);
    }

    public void start(Path cwd) {
        try {
            Files.createDirectories(cwd);
            List<String> command = buildCommand();
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(cwd.toFile());
            builder.redirectErrorStream(false);
            if (profile.nodePath() != null && !profile.nodePath().isBlank()) {
                String path = builder.environment().get("PATH");
                builder.environment().put("PATH", profile.nodePath() + ";" + (path == null ? "" : path));
            }
            process = builder.start();
            writer = new BufferedWriter(new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8));

            Thread stdout = new Thread(this::readStdout, "acp-stdout");
            stdout.setDaemon(true);
            stdout.start();
            Thread stderr = new Thread(this::readStderr, "acp-stderr");
            stderr.setDaemon(true);
            stderr.start();
        } catch (IOException e) {
            throw new IllegalStateException("启动 ACP Agent 失败: " + e.getMessage(), e);
        }
    }

    public boolean isAlive() {
        Process p = process;
        return p != null && p.isAlive() && !closed.get();
    }

    public void onNotification(Consumer<JsonNode> handler) {
        this.notificationHandler = handler;
    }

    public void onRequest(Consumer<JsonNode> handler) {
        this.requestHandler = handler;
    }

    public JsonNode request(String method, Object params, long timeoutMs) {
        long id = nextId.incrementAndGet();
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.set("params", objectMapper.valueToTree(params));

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        send(request);
        try {
            JsonNode response = future.get(Math.max(1000, timeoutMs), TimeUnit.MILLISECONDS);
            JsonNode error = response.get("error");
            if (error != null && !error.isNull()) {
                throw new AcpException(
                        error.path("code").asInt(-32603),
                        error.path("message").asText("ACP request failed"));
            }
            JsonNode result = response.get("result");
            return result == null ? objectMapper.nullNode() : result;
        } catch (TimeoutException e) {
            throw new IllegalStateException("ACP 请求超时(" + timeoutMs + "ms): " + method, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ACP 请求被中断: " + method, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof AcpException acp) {
                throw acp;
            }
            throw new IllegalStateException("ACP 请求失败: " + method + ": " + cause.getMessage(), cause);
        } finally {
            pending.remove(id);
        }
    }

    public void respond(Object id, Object result) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", objectMapper.valueToTree(id));
        response.set("result", objectMapper.valueToTree(result));
        send(response);
    }

    public void respondError(Object id, int code, String message) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", objectMapper.valueToTree(id));
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        send(response);
    }

    private List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            cmd.add("cmd");
            cmd.add("/c");
        }
        cmd.add(profile.command());
        if (profile.args() != null && !profile.args().isEmpty()) {
            cmd.addAll(profile.args());
        }
        cmd.addAll(extraArgs);
        if (profile.useMcpConfigFile()) {
            cmd.add("--allowed-mcp-server-names");
            cmd.add(profile.mcpServerName());
        }
        if (profile.approvalMode() != null && !profile.approvalMode().isBlank()) {
            cmd.add("--approval-mode");
            cmd.add(profile.approvalMode());
        }
        if (profile.authType() != null && !profile.authType().isBlank()) {
            cmd.add("--auth-type");
            cmd.add(profile.authType());
        }
        if (profile.apiKey() != null && !profile.apiKey().isBlank()) {
            cmd.add("--openai-api-key");
            cmd.add(profile.apiKey());
        }
        if (profile.baseUrl() != null && !profile.baseUrl().isBlank()) {
            cmd.add("--openai-base-url");
            cmd.add(profile.baseUrl());
        }
        if (profile.model() != null && !profile.model().isBlank()) {
            cmd.add("--model");
            cmd.add(profile.model());
        }
        return cmd;
    }

    private void readStdout() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node;
                try {
                    node = objectMapper.readTree(line);
                } catch (Exception e) {
                    System.out.println("[Acp] 非 JSON 输出: " + line);
                    continue;
                }
                if (node.has("id") && (node.has("result") || node.has("error"))) {
                    long id = node.path("id").asLong();
                    CompletableFuture<JsonNode> future = pending.get(id);
                    if (future != null) {
                        future.complete(node);
                    }
                } else if (node.has("method")) {
                    if (node.has("id")) {
                        requestHandler.accept(node);
                    } else {
                        notificationHandler.accept(node);
                    }
                }
            }
        } catch (IOException e) {
            if (!closed.get()) {
                System.out.println("[Acp] 读取 stdout 失败: " + e.getMessage());
            }
        } finally {
            failAll(new IllegalStateException("ACP Agent 进程已退出"));
        }
    }

    private void readStderr() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("[Acp][stderr] " + line);
            }
        } catch (IOException ignored) {
            // 进程退出时忽略
        }
    }

    private synchronized void send(JsonNode node) {
        if (writer == null) {
            throw new IllegalStateException("ACP 连接尚未启动");
        }
        try {
            writer.write(objectMapper.writeValueAsString(node));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            throw new IllegalStateException("写入 ACP 消息失败: " + e.getMessage(), e);
        }
    }

    private void failAll(Throwable error) {
        pending.forEach((id, future) -> future.completeExceptionally(error));
        pending.clear();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        failAll(new IllegalStateException("ACP 连接已关闭"));
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (IOException ignored) {
            // 忽略关闭失败
        }
        Process p = process;
        if (p != null) {
            p.destroy();
            try {
                if (!p.waitFor(3, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
    }
}
