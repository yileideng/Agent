package com.agent.demo.acpagentserver.acp_server_demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 最小外部 ACP Server demo。
 *
 * <p>接入一个外部 ACP Server 只需要满足：
 * <ol>
 *   <li>暴露一个 WebSocket 地址，例如 {@code ws://localhost:8095/acp}；</li>
 *   <li>每个 text frame 放一条 JSON-RPC 2.0 消息；</li>
 *   <li>处理 Gateway 发来的 {@code initialize}、{@code session/new}、
 *       {@code session/prompt}，并用同一个 {@code id} 返回响应；</li>
 *   <li>主动发送 {@code session/update} 通知，驱动 Gateway 的流式输出；</li>
 *   <li>需要权限时发送 {@code session/request_permission} 请求。</li>
 * </ol>
 *
 * <p>本 demo 不自己实现 Agent，而是把 WS 帧透明代理到本地
 * {@code qwen --acp} 的 stdin/stdout。要接入自己的 Agent，只需要替换
 * {@link #startQwen(WebSocketSession)} 这一处传输逻辑：
 * 如果 Agent 已经支持 ACP over WS，直接把它作为对端即可；
 * 如果 Agent 只有 stdio，就按这里的做法做一个 WS ↔ stdio 代理。
 *
 * <p>不追求连接池、鉴权、可扩展性，只用于演示接入流程。
 */
@Component
public class AcpWebSocketHandler extends TextWebSocketHandler {

    private final String command;
    private final String nodePath;
    private final String authType;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final String approvalMode;
    private final Map<String, Bridge> bridges = new ConcurrentHashMap<>();

    public AcpWebSocketHandler(
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
        this.apiKey = (apiKey == null || apiKey.isBlank())
                ? System.getenv("OPENAI_API_KEY")
                : apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.approvalMode = approvalMode;
        if (this.apiKey == null || this.apiKey.isBlank()) {
            System.out.println("[AcpAgentServer] WARN 未配置 qwen.api-key / AGENT_ACP_API_KEY / OPENAI_API_KEY");
        }
    }

    /**
     * 每个 WS 连接对应一个 Agent 会话/进程。
     * 真实外部 Agent 可以在这里建立到远端 ACP Server 的连接，而不是启动本地进程。
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        try {
            Bridge bridge = startQwen(session);
            bridges.put(session.getId(), bridge);
            System.out.println("[AcpAgentServer] " + session.getId() + " 已启动 Qwen ACP");
        } catch (Exception e) {
            System.out.println("[AcpAgentServer] 启动 Qwen 失败: " + e.getMessage());
            session.close(CloseStatus.SERVER_ERROR.withReason("Qwen start failed"));
        }
    }

    /**
     * Gateway -> 外部 Agent：把 ACP JSON-RPC 请求转给 Agent。
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Bridge bridge = bridges.get(session.getId());
        if (bridge == null) {
            closeQuietly(session, CloseStatus.SERVER_ERROR);
            return;
        }
        try {
            bridge.send(message.getPayload());
        } catch (Exception e) {
            System.out.println("[AcpAgentServer] 写入 Qwen 失败: " + e.getMessage());
            closeQuietly(session, CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Bridge bridge = bridges.remove(session.getId());
        if (bridge != null) {
            bridge.close();
        }
        System.out.println("[AcpAgentServer] " + session.getId() + " 已关闭: " + status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        System.out.println("[AcpAgentServer] 传输错误: " + exception.getMessage());
        closeQuietly(session, CloseStatus.SERVER_ERROR);
    }

    /**
     * 这是唯一和具体 Agent 耦合的地方：启动 {@code qwen --acp}。
     *
     * <p>要接入其他 Agent：
     * <ul>
     *   <li>如果它有 stdio ACP 模式：改 command/args 即可；</li>
     *   <li>如果它已经有 WS ACP Server：删掉进程启动，直接把 WS 帧转发过去；</li>
     *   <li>如果它是私有协议：在这里做协议转换，输出仍然是 ACP JSON-RPC。</li>
     * </ul>
     */
    private Bridge startQwen(WebSocketSession session) throws IOException {
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

        ProcessBuilder builder = new ProcessBuilder(cmd);
        builder.directory(Path.of(System.getProperty("java.io.tmpdir")).toFile());
        builder.redirectErrorStream(false);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.environment().put("OPENAI_API_KEY", apiKey);
        }
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.environment().put("OPENAI_BASE_URL", baseUrl);
        }
        if (nodePath != null && !nodePath.isBlank()) {
            String path = builder.environment().get("PATH");
            builder.environment().put("PATH", nodePath + ";" + (path == null ? "" : path));
        }
        return new Bridge(builder.start(),
                message -> sendToClient(session, message),
                () -> closeQuietly(session, CloseStatus.SERVER_ERROR));
    }

    /**
     * 外部 Agent -> Gateway：把 Agent 的 JSON-RPC 响应/通知送到 WS。
     */
    private void sendToClient(WebSocketSession session, String message) {
        if (!session.isOpen()) {
            return;
        }
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(message));
            }
        } catch (IOException e) {
            System.out.println("[AcpAgentServer] 发送 WS 失败: " + e.getMessage());
            closeQuietly(session, CloseStatus.SERVER_ERROR);
        }
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (IOException ignored) {
            // 忽略
        }
    }

    /**
     * 一个 Qwen 子进程的 stdin/stdout 读写器。
     */
    private static final class Bridge {

        private final Process process;
        private final BufferedWriter writer;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Bridge(Process process, java.util.function.Consumer<String> onMessage, Runnable onExit) {
            this.process = process;
            this.writer = new BufferedWriter(new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8));

            Thread stdout = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.isBlank()) {
                            onMessage.accept(line);
                        }
                    }
                } catch (IOException e) {
                    System.out.println("[AcpAgentServer] 读取 Qwen stdout 失败: " + e.getMessage());
                } finally {
                    if (!closed.get()) {
                        onExit.run();
                    }
                }
            }, "qwen-acp-stdout");
            stdout.setDaemon(true);
            stdout.start();

            Thread stderr = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("[Qwen][stderr] " + line);
                    }
                } catch (IOException ignored) {
                    // 进程退出
                }
            }, "qwen-acp-stderr");
            stderr.setDaemon(true);
            stderr.start();
        }

        private synchronized void send(String message) {
            try {
                writer.write(message);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                throw new IllegalStateException("写入 Qwen stdin 失败: " + e.getMessage(), e);
            }
        }

        private void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                writer.close();
            } catch (IOException ignored) {
                // 忽略
            }
            process.destroy();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
}
