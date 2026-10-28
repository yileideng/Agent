package com.agent.demo.clientagent.acp_agent.impl.adaptor;

import com.agent.demo.clientagent.acp_agent.AcpAgent;
import com.agent.demo.clientagent.core.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Codex 0.149 app-server JSONL transport. No shell interpolation or global config writes. */
@Component
public class CodexAgentAdaptor implements AcpAgent {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String command, model, modelProvider, mcpUrl, workDir, languagePrompt;
    private final long timeoutSeconds;
    private final AgentSessionStore sessions;

    public CodexAgentAdaptor(@Value("${agent.codex.command:codex}") String command,
                             @Value("${agent.codex.model:}") String model,
                             @Value("${agent.codex.model-provider:}") String modelProvider,
                             @Value("${agent.codex.mcp-url:}") String mcpUrl,
                             @Value("${agent.codex.timeout-seconds:180}") long timeoutSeconds,
                             @Value("${agent.codex.work-dir:}") String workDir,
                             @Value("${agent.codex.language-prompt:请始终使用简体中文回答用户。}") String languagePrompt,
                             AgentSessionStore sessions) {
        this.command = command; this.model = model; this.modelProvider = modelProvider; this.mcpUrl = mcpUrl;
        this.timeoutSeconds = timeoutSeconds; this.workDir = workDir;
        this.languagePrompt = languagePrompt; this.sessions = sessions;
    }

    @Override public String name() { return "codex"; }
    @Override public String description() { return "Codex Agent（app-server，复用本机 CLI 登录）"; }
    @Override public List<String> capabilities() {
        return List.of("chat", "streaming", "thinking", "session-resume", "mcp", "client-tool-call");
    }
    @Override public boolean available() { return executable() != null; }
    @Override public String unavailableReason() {
        return available() ? null : "Codex 不可用：请安装 Codex CLI 并设置 agent.codex.command / AGENT_CODEX_COMMAND 为可执行文件路径";
    }
    @Override public AgentResponse chat(AgentRequest request) { return run(request, AgentStreamSink.NOOP); }
    @Override public void chatStream(AgentRequest request, AgentStreamSink sink) {
        try { run(request, sink); }
        catch (Exception e) { sink.error("AGENT_ERROR", e.getMessage()); }
    }

    private AgentResponse run(AgentRequest request, AgentStreamSink sink) {
        String exe = executable();
        if (exe == null) throw new IllegalStateException(unavailableReason());
        String sessionId = sessions.resolveSessionId(request.clientId(), request.endUserId(), request.sessionId());
        var state = sessions.getOrCreate(name(), request.clientId(), request.endUserId(), sessionId);
        // Same conversation must not race two processes that both resume the same thread.
        synchronized (state) {
            Path cwd = workspace(request, sessionId);
            try (Rpc rpc = new Rpc(startProcess(exe, cwd), timeoutSeconds)) {
                rpc.call("initialize", JSON.createObjectNode().set("clientInfo", JSON.valueToTree(
                        Map.of("name", "agent_gateway", "version", "1.0"))));
                rpc.notify("initialized", JSON.createObjectNode());
                ObjectNode params = JSON.createObjectNode().put("cwd", cwd.toString())
                        .put("sandbox", "read-only").put("approvalPolicy", "never")
                        .put("developerInstructions", instructions(request.clientPrompt()));
                if (model != null && !model.isBlank()) params.put("model", model);
                if (modelProvider != null && !modelProvider.isBlank()) params.put("modelProvider", modelProvider);
                String url = mcpUrlFor(request, sessionId);
                if (url != null) {
                    params.putObject("config").putObject("mcp_servers").putObject("client-tools")
                            .put("url", url).put("enabled", true)
                            .put("default_tools_approval_mode", "approve");
                }
                String previous = state.externalSessionId();
                if (previous != null) params.put("threadId", previous);
                JsonNode thread = rpc.call(previous == null ? "thread/start" : "thread/resume", params);
                String threadId = thread.path("thread").path("id").asText();
                if (threadId.isBlank()) throw new IllegalStateException("Codex 未返回 thread.id");
                state.bindExternalSessionId(threadId);
                if (url != null) {
                    rpc.awaitMcpReady(threadId, "client-tools");
                    // Resolving the thread-scoped inventory also catches discovery errors.
                    // An empty tool catalog is valid for clients with no registered tools.
                    rpc.call("mcpServerStatus/list", JSON.createObjectNode().put("threadId", threadId));
                }
                ObjectNode turn = JSON.createObjectNode().put("threadId", threadId);
                turn.putArray("input").addObject().put("type", "text").put("text", request.userPrompt())
                        .putArray("text_elements");
                String turnId = rpc.call("turn/start", turn).path("turn").path("id").asText();
                if (turnId.isBlank()) throw new IllegalStateException("Codex 未返回 turn.id");
                StringBuilder answer = new StringBuilder();
                Set<String> streamedItems = new HashSet<>();
                while (true) {
                    JsonNode event = rpc.nextNotification();
                    JsonNode p = event.path("params");
                    if (!threadId.equals(p.path("threadId").asText())) continue;
                    String eventTurn = p.path("turnId").asText(p.path("turn").path("id").asText());
                    if (!turnId.equals(eventTurn)) continue;
                    switch (event.path("method").asText()) {
                        case "item/agentMessage/delta" -> {
                            streamedItems.add(p.path("itemId").asText());
                            emit(answer, sink, p.path("delta").asText());
                        }
                        case "item/reasoning/summaryTextDelta" -> sink.thinking(p.path("delta").asText());
                        case "item/completed" -> {
                            JsonNode item = p.path("item");
                            if ("agentMessage".equals(item.path("type").asText())
                                    && !streamedItems.contains(item.path("id").asText())) {
                                emit(answer, sink, item.path("text").asText());
                            }
                        }
                        case "error" -> {
                            if (!p.path("willRetry").asBoolean(false))
                                throw new IllegalStateException("Codex turn 失败：" + p.path("error").path("message").asText("unknown error"));
                        }
                        case "turn/completed" -> {
                            JsonNode completed = p.path("turn");
                            if (!"completed".equals(completed.path("status").asText()))
                                throw new IllegalStateException("Codex turn " + completed.path("status").asText()
                                        + "：" + completed.path("error").path("message").asText("未完成"));
                            if (answer.isEmpty()) throw new IllegalStateException("Codex turn 已结束但未返回正文");
                            sink.finish(sessionId, answer.toString());
                            return new AgentResponse(sessionId, answer.toString());
                        }
                        default -> { }
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("无法启动或读取 Codex app-server，请检查可执行文件与 CLI 登录状态", e);
            }
        }
    }

    // Package-visible seam: tests launch a fake Java CLI process, never a model.
    Process startProcess(String exe, Path cwd) throws IOException {
        return new ProcessBuilder(exe, "app-server", "--listen", "stdio://")
                .directory(cwd.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }

    private static void emit(StringBuilder answer, AgentStreamSink sink, String text) {
        if (!text.isEmpty()) { answer.append(text); sink.delta(text); }
    }
    private String instructions(String clientPrompt) {
        return (clientPrompt == null ? "" : clientPrompt) + "\n" + (languagePrompt == null ? "" : languagePrompt);
    }
    String mcpUrlFor(AgentRequest r, String sessionId) {
        if (mcpUrl == null || mcpUrl.isBlank()) return null;
        return mcpUrl.replaceAll("/+$", "") + "/" + encode(r.clientId()).replace("+", "%20")
                + "?endUserId=" + encode(r.endUserId()) + "&sessionId=" + encode(sessionId)
                + "&chatId=" + encode(r.chatId());
    }
    private static String encode(String s) { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8); }
    private Path workspace(AgentRequest r, String sessionId) {
        Path root = workDir == null || workDir.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "codex-agent-workspaces") : Path.of(workDir);
        try {
            // Hash rather than sanitized identifiers: collision-free names and no traversal (including '..').
            String scope = JSON.writeValueAsString(Arrays.asList(r.clientId(), r.endUserId(), sessionId));
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(scope.getBytes(StandardCharsets.UTF_8)));
            return Files.createDirectories(root.toAbsolutePath().normalize().resolve(digest));
        } catch (Exception e) { throw new IllegalStateException("创建 Codex 会话工作目录失败", e); }
    }
    private String executable() {
        if (command == null || command.isBlank()) return null;
        try {
            Path direct = Path.of(command);
            if (direct.isAbsolute() || command.contains("/") || command.contains("\\"))
                return Files.isRegularFile(direct) && Files.isExecutable(direct) ? direct.toAbsolutePath().toString() : null;
        } catch (InvalidPathException e) { return null; }
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            for (String suffix : List.of("", ".exe")) {
                try {
                    Path candidate = Path.of(dir, command + suffix);
                    if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate.toAbsolutePath().toString();
                } catch (InvalidPathException ignored) { /* Ignore malformed PATH entries. */ }
            }
        }
        return null;
    }

    /** One request-wide deadline includes initialization, MCP discovery, model and tool latency. */
    static final class Rpc implements AutoCloseable {
        private static final JsonNode EOF = JSON.createObjectNode().put("eof", true);
        private final Process process;
        private final BufferedWriter writer;
        private final BlockingQueue<JsonNode> incoming = new LinkedBlockingQueue<>();
        private final Deque<JsonNode> notifications = new ArrayDeque<>();
        private final long deadline;
        private final Thread reader;
        private long id;
        Rpc(Process process, long seconds) {
            this.process = process;
            this.deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, seconds));
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            reader = new Thread(() -> {
                try (var input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = input.readLine()) != null) {
                        if (!line.isBlank()) incoming.add(JSON.readTree(line));
                    }
                } catch (IOException ignored) {
                    // Do not forward raw subprocess diagnostics: they can contain local credentials.
                } finally { incoming.add(EOF); }
            }, "codex-app-server-reader");
            reader.setDaemon(true); reader.start();
        }
        JsonNode call(String method, JsonNode params) throws IOException {
            long requestId = ++id;
            send(JSON.createObjectNode().put("id", requestId).put("method", method).set("params", params));
            while (true) {
                JsonNode message = receive();
                if (message.has("method")) {
                    if (message.has("id")) rejectRequest(message);
                    else notifications.add(message);
                } else if (message.path("id").asLong(-1) == requestId) {
                    if (message.has("error")) throw new IllegalStateException("Codex " + method + " 失败："
                            + message.path("error").path("message").asText("RPC error"));
                    return message.path("result");
                }
            }
        }
        void awaitMcpReady(String threadId, String serverName) throws IOException {
            List<JsonNode> deferred = new ArrayList<>();
            try {
                while (true) {
                    JsonNode notification = nextNotification();
                    JsonNode p = notification.path("params");
                    String eventThread = p.path("threadId").asText("");
                    if (!"mcpServer/startupStatus/updated".equals(notification.path("method").asText())
                            || !serverName.equals(p.path("name").asText())
                            || (!eventThread.isEmpty() && !threadId.equals(eventThread))) {
                        deferred.add(notification);
                        continue;
                    }
                    switch (p.path("status").asText()) {
                        case "ready" -> { return; }
                        case "failed", "cancelled" -> throw new IllegalStateException(
                                "Codex MCP " + serverName + " 启动失败：" + p.path("error").asText(p.path("status").asText()));
                        default -> { }
                    }
                }
            } catch (IllegalStateException e) {
                if (e.getMessage() != null && e.getMessage().contains("超时"))
                    throw new IllegalStateException("等待 Codex MCP " + serverName + " 就绪超时，已终止进程", e);
                throw e;
            } finally {
                for (int i = deferred.size() - 1; i >= 0; i--) notifications.addFirst(deferred.get(i));
            }
        }
        void notify(String method, JsonNode params) throws IOException {
            send(JSON.createObjectNode().put("method", method).set("params", params));
        }
        JsonNode nextNotification() throws IOException {
            while (true) {
                JsonNode message = notifications.isEmpty() ? receive() : notifications.remove();
                if (!message.has("method")) continue;
                if (message.has("id")) { rejectRequest(message); continue; }
                return message;
            }
        }
        private JsonNode receive() {
            try {
                long remaining = deadline - System.nanoTime();
                JsonNode node = remaining <= 0 ? null : incoming.poll(remaining, TimeUnit.NANOSECONDS);
                if (node == null) throw new IllegalStateException("Codex app-server 执行超时，已终止进程");
                if (node == EOF) throw new IllegalStateException("Codex app-server 意外退出或协议输出无效，请检查 CLI 登录状态");
                return node;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); throw new IllegalStateException("Codex 调用被中断", e);
            }
        }
        private void rejectRequest(JsonNode message) throws IOException {
            // Never grant shell/file/network escalation or block waiting for interactive input.
            ObjectNode reply = JSON.createObjectNode().set("id", message.get("id"));
            switch (message.path("method").asText()) {
                case "item/commandExecution/requestApproval", "item/fileChange/requestApproval" ->
                        reply.putObject("result").put("decision", "decline");
                case "mcpServer/elicitation/request" -> reply.putObject("result").put("action", "decline");
                default -> reply.putObject("error").put("code", -32601)
                        .put("message", "Gateway runs Codex noninteractively; this request is not supported");
            }
            send(reply);
        }
        private void send(JsonNode node) throws IOException {
            writer.write(JSON.writeValueAsString(node)); writer.newLine(); writer.flush();
        }
        @Override public void close() {
            List<ProcessHandle> children = process.descendants().toList();
            // EOF lets app-server flush its rollout before a later process resumes this thread.
            try { writer.close(); } catch (IOException ignored) { }
            try {
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    children.forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
                children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
                reader.join(1000);
            } catch (InterruptedException e) {
                children.forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }
}
