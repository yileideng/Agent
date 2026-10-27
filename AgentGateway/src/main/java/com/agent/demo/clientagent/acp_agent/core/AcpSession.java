package com.agent.demo.clientagent.acp_agent.core;

import com.agent.demo.clientagent.core.AgentRequest;
import com.agent.demo.clientagent.core.AgentStreamSink;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 一个 ACP 会话：持有 Agent 子进程、ACP sessionId，并处理 session/update。
 *
 * <p>Qwen Code 的 ACP 模式一个进程可以承载多个 session；MVP 先按本地会话
 * 一个进程实现，后续再复用进程。
 */
public final class AcpSession implements AutoCloseable {

    private final AcpAgentProfile profile;
    private final String localSessionId;
    private final String clientId;
    private final String endUserId;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReentrantLock promptLock = new ReentrantLock();

    private AcpConnection connection;
    private volatile String acpSessionId;
    private volatile StringBuilder answer;
    private volatile AgentStreamSink sink;

    public AcpSession(AcpAgentProfile profile,
                      String localSessionId,
                      String clientId,
                      String endUserId) {
        this.profile = profile;
        this.localSessionId = localSessionId;
        this.clientId = clientId;
        this.endUserId = endUserId;
    }

    public synchronized void ensureStarted(AgentRequest request) {
        if (connection != null && connection.isAlive() && acpSessionId != null) {
            return;
        }
        close();
        Path cwd = prepareWorkDir();
        List<String> extraArgs = List.of();
        ArrayNode sessionServers = mcpServers();
        if (profile.useMcpConfigFile() && mcpServerUrl() != null) {
            Path configFile = writeMcpConfig(cwd);
            extraArgs = List.of("--mcp-config", configFile.toString());
            // 已通过 --mcp-config 注入，session/new 不再重复声明，避免同名 MCP 冲突
            sessionServers = objectMapper.createArrayNode();
        }
        AcpConnection conn = profile.wsUrl() != null && !profile.wsUrl().isBlank()
                ? new WsAcpConnection(profile)
                : new StdioAcpConnection(profile, extraArgs);
        conn.onNotification(this::handleNotification);
        conn.onRequest(this::handleRequest);
        conn.start(cwd);

        ObjectNode init = objectMapper.createObjectNode();
        init.put("protocolVersion", 1);
        ObjectNode caps = init.putObject("clientCapabilities");
        ObjectNode fs = caps.putObject("fs");
        fs.put("readTextFile", false);
        fs.put("writeTextFile", false);
        caps.put("terminal", false);
        ObjectNode info = init.putObject("clientInfo");
        info.put("name", "agent-gateway");
        info.put("title", "Agent Gateway");
        info.put("version", "0.1");
        JsonNode initResult = conn.request("initialize", init, 30_000);

        ObjectNode newSession = objectMapper.createObjectNode();
        newSession.put("cwd", cwd.toAbsolutePath().toString());
        newSession.set("mcpServers", sessionServers);
        try {
            JsonNode sessionResult = conn.request("session/new", newSession, 60_000);
            String sid = sessionResult.path("sessionId").asText(null);
            if (sid == null || sid.isBlank()) {
                throw new IllegalStateException("ACP session/new 未返回 sessionId");
            }
            this.acpSessionId = sid;
        } catch (AcpException e) {
            // 有些 Agent 在未认证时先返回 auth 错误；用 Qwen 的 openai 方法重试一次
            if (e.code() == -32000 || e.getMessage().toLowerCase().contains("auth")) {
                JsonNode authMethods = initResult.path("authMethods");
                if (authMethods.isArray() && !authMethods.isEmpty()) {
                    ObjectNode auth = objectMapper.createObjectNode();
                    auth.put("methodId", authMethods.get(0).path("id").asText("openai"));
                    conn.request("authenticate", auth, 30_000);
                    JsonNode sessionResult = conn.request("session/new", newSession, 60_000);
                    this.acpSessionId = sessionResult.path("sessionId").asText(null);
                }
            }
            if (acpSessionId == null) {
                throw e;
            }
        }
        this.connection = conn;
    }

    /**
     * 发送一轮 prompt，阻塞到 ACP 返回 stopReason；文本通过 sink 流式回调。
     */
    public String prompt(AgentRequest request, StringBuilder answer, AgentStreamSink sink) {
        ensureStarted(request);
        promptLock.lock();
        try {
            this.answer = answer;
            this.sink = sink;

            ArrayNode blocks = objectMapper.createArrayNode();
            if (request.clientPrompt() != null && !request.clientPrompt().isBlank()) {
                ObjectNode system = blocks.addObject();
                system.put("type", "text");
                system.put("text", "[客户端系统提示]\n" + request.clientPrompt().trim());
            }
            ObjectNode user = blocks.addObject();
            user.put("type", "text");
            user.put("text", request.userPrompt());

            ObjectNode params = objectMapper.createObjectNode();
            params.put("sessionId", acpSessionId);
            params.set("prompt", blocks);
            connection.request("session/prompt", params, profile.timeoutSeconds() * 1000);
            return answer.toString();
        } finally {
            this.answer = null;
            this.sink = null;
            promptLock.unlock();
        }
    }

    private void handleNotification(JsonNode node) {
        if (!"session/update".equals(node.path("method").asText())) {
            return;
        }
        JsonNode params = node.path("params");
        if (acpSessionId != null && !acpSessionId.equals(params.path("sessionId").asText())) {
            return;
        }
        JsonNode update = params.path("update");
        String type = update.path("sessionUpdate").asText("");
        switch (type) {
            case "agent_message_chunk" -> {
                String text = extractText(update.get("content"));
                if (!text.isEmpty() && sink != null) {
                    if (answer != null) {
                        answer.append(text);
                    }
                    sink.delta(text);
                }
            }
            case "agent_thought_chunk" -> {
                String text = extractText(update.get("content"));
                if (!text.isEmpty() && sink != null) {
                    sink.thinking(text);
                }
            }
            case "tool_call", "tool_call_update" -> {
                String status = update.path("status").asText("");
                System.out.println("[Acp] tool " + update.path("toolCallId").asText("")
                        + " status=" + status
                        + " title=" + update.path("title").asText(""));
                if ("failed".equals(status)) {
                    System.out.println("[Acp] tool failed content=" + update.path("content"));
                }
            }
            default -> {
                // plan / usage / notice 暂不映射
            }
        }
    }

    private void handleRequest(JsonNode node) {
        String method = node.path("method").asText("");
        JsonNode id = node.get("id");
        if ("session/request_permission".equals(method)) {
            // 验证期允许自动放行；生产环境需要转发到客户端确认
            System.out.println("[Acp] permission request=" + node);
            boolean allow = "yolo".equalsIgnoreCase(profile.approvalMode());
            JsonNode options = node.path("params").path("options");
            String optionId = null;
            if (allow && options.isArray()) {
                for (JsonNode option : options) {
                    String kind = option.path("kind").asText("");
                    if ("allow_once".equals(kind) || "allow_always".equals(kind)) {
                        optionId = option.path("optionId").asText(null);
                        break;
                    }
                }
            }
            ObjectNode result = objectMapper.createObjectNode();
            ObjectNode outcome = result.putObject("outcome");
            if (optionId == null) {
                outcome.put("outcome", "cancelled");
            } else {
                outcome.put("outcome", "selected");
                outcome.put("optionId", optionId);
            }
            connection.respond(id, result);
            return;
        }
        connection.respondError(id, -32601, "Unsupported client method: " + method);
    }

    private String extractText(JsonNode content) {
        if (content == null || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode item : content) {
                text.append(extractText(item));
            }
            return text.toString();
        }
        if ("text".equals(content.path("type").asText())) {
            return content.path("text").asText("");
        }
        return content.path("text").asText("");
    }

    private ArrayNode mcpServers() {
        ArrayNode servers = objectMapper.createArrayNode();
        String url = mcpServerUrl();
        if (url == null) {
            return servers;
        }
        ObjectNode server = servers.addObject();
        server.put("name", profile.mcpServerName());
        // ACP zMcpServerHttp 要求 type/name/url/headers 四个字段；
        // headers 是必填数组，不能省略，否则 Qwen 报 invalid_union。
        server.put("type", "http");
        server.put("url", url);
        server.putArray("headers");
        return servers;
    }

    private String mcpServerUrl() {
        if (profile.mcpUrl() == null || profile.mcpUrl().isBlank()) {
            return null;
        }
        String base = profile.mcpUrl().endsWith("/")
                ? profile.mcpUrl().substring(0, profile.mcpUrl().length() - 1)
                : profile.mcpUrl();
        StringBuilder url = new StringBuilder(base)
                .append('/').append(URLEncoder.encode(clientId, StandardCharsets.UTF_8));
        char sep = '?';
        if (endUserId != null && !endUserId.isBlank()) {
            url.append(sep).append("endUserId=")
                    .append(URLEncoder.encode(endUserId, StandardCharsets.UTF_8));
            sep = '&';
        }
        if (localSessionId != null && !localSessionId.isBlank()) {
            url.append(sep).append("sessionId=")
                    .append(URLEncoder.encode(localSessionId, StandardCharsets.UTF_8));
        }
        return url.toString();
    }

    private Path writeMcpConfig(Path cwd) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode servers = root.putObject("mcpServers");
        ObjectNode server = servers.putObject(profile.mcpServerName());
        server.put("httpUrl", mcpServerUrl());
        // Qwen 0.26 的 ACP 会话默认把 MCP 工具放进 deferred 集合，模型看不到；
        // alwaysLoadTools 让工具在会话启动时直接进入工具表。
        server.put("alwaysLoadTools", true);
        Path file = cwd.resolve("mcp-config.json");
        try {
            objectMapper.writeValue(file.toFile(), root);
            return file;
        } catch (Exception e) {
            throw new IllegalStateException("写入 ACP MCP 配置失败: " + file, e);
        }
    }

    private Path prepareWorkDir() {
        String base = profile.workDir() == null || profile.workDir().isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "client-agent-workspaces").toString()
                : profile.workDir();
        Path dir = Path.of(base).resolve(safe(clientId)).resolve(safe(localSessionId));
        try {
            Files.createDirectories(dir);
            return dir;
        } catch (Exception e) {
            throw new IllegalStateException("创建 ACP 工作目录失败: " + dir, e);
        }
    }

    private String safe(String value) {
        String safe = value == null ? "unknown" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
        return safe.length() > 80 ? safe.substring(0, 80) : safe;
    }

    @Override
    public synchronized void close() {
        if (connection != null) {
            connection.close();
            connection = null;
        }
        acpSessionId = null;
    }
}
