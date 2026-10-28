package com.agent.demo.clientagent.acp_agent.impl.adaptor;

import com.agent.demo.clientagent.core.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class CodexAgentAdaptorTest {
    static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;
    private final AgentSessionStore sessions = new AgentSessionStore(120);
    private final List<Process> processes = new ArrayList<>();
    private String javaExe() {
        return Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
    }
    private Path transcript() { return temp.resolve("transcript.jsonl"); }
    private CodexAgentAdaptor fake(String mode, String mcp, long timeout) {
        return new CodexAgentAdaptor(javaExe(), "test-model", "test-provider", mcp, timeout, temp.toString(), "中文约束", sessions) {
            @Override Process startProcess(String exe, Path cwd) throws IOException {
                var p = new ProcessBuilder(javaExe(), "-cp", System.getProperty("java.class.path"),
                        FakeCli.class.getName(), mode, transcript().toString()).directory(cwd.toFile())
                        .redirectError(ProcessBuilder.Redirect.INHERIT).start();
                processes.add(p); return p;
            }
        };
    }
    private AgentRequest request(String session, String chat) {
        return new AgentRequest("client A", "用户", "hello\nworld", "客户端规则\n第二行", session, chat);
    }
    private List<JsonNode> transcriptLines() throws IOException {
        var result = new ArrayList<JsonNode>();
        for (String line : Files.readAllLines(transcript())) result.add(JSON.readTree(line));
        return result;
    }
    static class Sink implements AgentStreamSink {
        List<String> deltas = new ArrayList<>(); String output, session, error; int finishes;
        public void delta(String text) { deltas.add(text); }
        public void finish(String session, String output) { this.session = session; this.output = output; finishes++; }
        public void error(String code, String message) { error = message; }
    }

    @Test void initializesStreamsBeforeCompletionAndResumesInSeparateProcess() throws Exception {
        var adaptor = fake("stream", "http://localhost:9999/mcp", 10);
        var sink = new Sink();
        adaptor.chatStream(request("session", "chat-1"), sink);
        assertNull(sink.error); assertEquals(List.of("你", "好"), sink.deltas);
        assertEquals("你好", sink.output); assertEquals(1, sink.finishes);
        var result = adaptor.chat(request(sink.session, "chat-2"));
        assertEquals(sink.session, result.sessionId()); assertEquals("你好", result.output());
        List<JsonNode> sent = transcriptLines();
        assertEquals("initialize", sent.get(0).path("method").asText());
        assertEquals("initialized", sent.get(1).path("method").asText());
        var starts = sent.stream().filter(n -> n.path("method").asText().startsWith("thread/")).toList();
        assertEquals(2, starts.size());
        assertEquals("thread/start", starts.get(0).path("method").asText());
        assertEquals("thread/resume", starts.get(1).path("method").asText());
        assertEquals("thread-123", starts.get(1).path("params").path("threadId").asText());
        for (var start : starts) {
            var p = start.path("params");
            assertEquals("read-only", p.path("sandbox").asText());
            assertEquals("never", p.path("approvalPolicy").asText());
            assertEquals("客户端规则\n第二行\n中文约束", p.path("developerInstructions").asText());
            assertEquals("test-model", p.path("model").asText());
            assertEquals("test-provider", p.path("modelProvider").asText());
            assertEquals("approve", p.path("config").path("mcp_servers").path("client-tools").path("default_tools_approval_mode").asText());
            assertTrue(Path.of(p.path("cwd").asText()).startsWith(temp));
        }
        String url = starts.get(1).path("params").path("config").path("mcp_servers").path("client-tools").path("url").asText();
        assertTrue(url.contains("/client%20A?endUserId=%E7%94%A8%E6%88%B7&sessionId="));
        assertTrue(url.endsWith("&chatId=chat-2"));
        assertEquals("hello\nworld", sent.stream().filter(n -> n.path("method").asText().equals("turn/start"))
                .findFirst().orElseThrow().path("params").path("input").get(0).path("text").asText());
        assertTrue(processes.stream().noneMatch(Process::isAlive));
        assertEquals(2, sent.stream().filter(n -> n.path("eof").asBoolean()).count());
        assertNull(sessions.getOrCreate("cli", "client A", "用户", sink.session).externalSessionId());
    }

    @Test void deltaReallyArrivesBeforeCliCompletes() {
        var adaptor = fake("stream", "", 10);
        var sink = new Sink() {
            @Override public void delta(String text) {
                assertTrue(processes.get(0).isAlive());
                assertEquals(0, finishes);
                super.delta(text);
            }
        };
        adaptor.chatStream(request("s", "c"), sink);
        assertNull(sink.error); assertEquals("你好", sink.output);
    }

    @Test void fallsBackToCompletedItemWithoutDuplicatingStreamedItems() {
        var sink = new Sink(); fake("aggregate", "", 10).chatStream(request("s", "c"), sink);
        assertNull(sink.error); assertEquals(List.of("你好"), sink.deltas); assertEquals("你好", sink.output);
    }
    @Test void rejectsUnexpectedApprovalAndElicitationWithoutHanging() throws Exception {
        var sink = new Sink(); fake("approval", "", 10).chatStream(request("s", "c"), sink);
        assertNull(sink.error); assertEquals("你好", sink.output);
        var replies = transcriptLines().stream().filter(n -> n.has("id") && !n.has("method")).toList();
        assertEquals("decline", replies.get(0).path("result").path("decision").asText());
        assertEquals("decline", replies.get(1).path("result").path("action").asText());
        assertEquals(-32601, replies.get(2).path("error").path("code").asInt());
    }
    @Test void keepsIdenticalSessionNamesIsolatedByEndUser() throws Exception {
        var adaptor = fake("stream", "", 10);
        var first = adaptor.chat(request("same", "c1"));
        var second = adaptor.chat(new AgentRequest("client A", "different-user", "hi", "", "same", "c2"));
        assertNotEquals(first.sessionId(), second.sessionId());
        var threads = transcriptLines().stream().filter(n -> n.path("method").asText().startsWith("thread/")).toList();
        assertEquals("thread/start", threads.get(1).path("method").asText());
        assertNotEquals(threads.get(0).path("params").path("cwd"), threads.get(1).path("params").path("cwd"));
    }
    @Test void retriableErrorDoesNotFinishOrDropFollowingDeltas() {
        var sink = new Sink(); fake("retry", "", 10).chatStream(request("s", "c"), sink);
        assertNull(sink.error); assertEquals("你好", sink.output); assertEquals(1, sink.finishes);
    }
    @Test void reportsRpcFailureAndFailedTurnAndUnexpectedExit() {
        for (String mode : List.of("rpc-error", "failed", "exit", "error-event", "empty")) {
            var sink = new Sink(); fake(mode, "", 10).chatStream(request(mode, "c"), sink);
            assertNotNull(sink.error, mode); assertEquals(0, sink.finishes);
        }
        assertTrue(processes.stream().noneMatch(Process::isAlive));
    }
    @Test void timesOutAndKillsChild() {
        var sink = new Sink(); long before = System.nanoTime();
        fake("timeout", "", 1).chatStream(request("s", "c"), sink);
        assertTrue(sink.error.contains("超时")); assertEquals(0, sink.finishes);
        assertTrue((System.nanoTime() - before) / 1_000_000 < 6000);
        assertFalse(processes.get(0).isAlive());
    }
    @Test void missingExecutableDoesNotBreakOtherAgents() {
        var adaptor = new CodexAgentAdaptor(temp.resolve("missing.exe").toString(), "", "", "", 1, "", "", sessions);
        assertFalse(adaptor.available()); assertTrue(adaptor.unavailableReason().contains("AGENT_CODEX_COMMAND"));
        var invalid = new CodexAgentAdaptor("invalid" + Character.toString(0), "", "", "", 1, "", "", sessions);
        assertFalse(invalid.available());
        var sink = new Sink(); adaptor.chatStream(request("s", "c"), sink);
        assertNotNull(sink.error); assertEquals(0, sink.finishes);
    }
    @Test void routesMcpInitializationDiscoveryAndToolRpcToCurrentChat() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp/", exchange -> {
            JsonNode rpc = JSON.readTree(exchange.getRequestBody());
            received.add(exchange.getRequestURI().toString() + " " + rpc.path("method").asText());
            String body = JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", rpc.path("id").asInt(),
                    "result", Map.of("content", List.of(Map.of("type", "text", "text", "readonly-result")))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var adaptor = fake("mcp", "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp", 10);
            assertEquals("你好", adaptor.chat(request("s", "tool-chat")).output());
            assertEquals(3, received.size());
            assertTrue(received.get(0).endsWith(" initialize"));
            assertTrue(received.get(1).endsWith(" tools/list")); assertTrue(received.get(2).endsWith(" tools/call"));
            for (String call : received) {
                assertTrue(call.contains("client%20A?endUserId="));
                assertTrue(call.contains("sessionId=client+A%3A")); assertTrue(call.contains("chatId=tool-chat"));
            }
        } finally { server.stop(0); }
    }

    @Test void waitsForMatchingMcpReadyAndAllowsAnEmptyToolCatalog() throws Exception {
        var sink = new Sink(); fake("mcp-delayed", "http://localhost/mcp", 10).chatStream(request("s", "c"), sink);
        assertNull(sink.error); assertEquals("你好", sink.output);
        var methods = transcriptLines().stream().map(n -> n.path("method").asText()).toList();
        assertTrue(methods.indexOf("mcpServerStatus/list") > methods.indexOf("thread/start"));
        assertTrue(methods.indexOf("turn/start") > methods.indexOf("mcpServerStatus/list"));
    }
    @Test void failsBeforeModelTurnWhenMcpStartupOrDiscoveryFails() throws Exception {
        for (String mode : List.of("mcp-failed", "mcp-cancelled", "mcp-list-error")) {
            var sink = new Sink(); fake(mode, "http://localhost/mcp", 10).chatStream(request(mode, "c"), sink);
            assertNotNull(sink.error); assertEquals(0, sink.finishes);
        }
        assertTrue(transcriptLines().stream().noneMatch(n -> "turn/start".equals(n.path("method").asText())));
        assertTrue(processes.stream().noneMatch(Process::isAlive));
    }
    @Test void mcpReadinessUsesTheRequestDeadline() {
        var sink = new Sink(); fake("mcp-timeout", "http://localhost/mcp", 1).chatStream(request("s", "c"), sink);
        assertNotNull(sink.error); assertTrue(sink.error.contains("MCP")); assertTrue(sink.error.contains("超时"));
        assertEquals(0, sink.finishes); assertFalse(processes.get(0).isAlive());
    }

    @Test void realModelListOnlyWhenExplicitlyRequested() throws Exception {
        String command = System.getProperty("codex.smoke.command", "");
        org.junit.jupiter.api.Assumptions.assumeFalse(command.isBlank());
        Process p = new ProcessBuilder(command, "app-server", "--listen", "stdio://")
                .directory(temp.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try (var rpc = new CodexAgentAdaptor.Rpc(p, 20)) {
            rpc.call("initialize", JSON.valueToTree(Map.of("clientInfo", Map.of("name", "gateway_model_check", "version", "1.0"))));
            rpc.notify("initialized", JSON.createObjectNode());
            JsonNode result = rpc.call("model/list", JSON.createObjectNode().put("limit", 50));
            for (JsonNode model : result.path("data"))
                System.out.println("Codex model: " + JSON.writeValueAsString(Map.of("model", model.path("model").asText(),
                        "id", model.path("id").asText(), "displayName", model.path("displayName").asText(),
                        "isDefault", model.path("isDefault").asBoolean())));
            JsonNode account = rpc.call("account/read", JSON.createObjectNode().put("refreshToken", false));
            System.out.println("Codex account type: " + account.path("account").path("type").asText());
            var thread = rpc.call("thread/start", JSON.createObjectNode().put("cwd", temp.toString())
                    .put("sandbox", "read-only").put("approvalPolicy", "never"));
            System.out.println("Codex selected model provider: " + thread.path("modelProvider").asText());
            assertFalse(result.path("data").isEmpty());
        }
    }

    private HttpServer startReadOnlyMcpStub(List<String> methods) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp/", exchange -> {
            if (!exchange.getRequestMethod().equals("POST")) { exchange.sendResponseHeaders(405, -1); exchange.close(); return; }
            JsonNode req = JSON.readTree(exchange.getRequestBody());
            String method = req.path("method").asText(); methods.add(method);
            if (!req.has("id")) { exchange.sendResponseHeaders(202, -1); exchange.close(); return; }
            Object result = switch (method) {
                case "initialize" -> Map.of("protocolVersion", req.path("params").path("protocolVersion").asText(),
                        "capabilities", Map.of("tools", Map.of()), "serverInfo", Map.of("name", "readonly-stub", "version", "1"));
                case "tools/list" -> Map.of("tools", List.of(Map.of("name", "read_only_test", "description", "Returns a constant without side effects",
                        "inputSchema", Map.of("type", "object", "properties", Map.of()))));
                case "tools/call" -> Map.of("content", List.of(Map.of("type", "text", "text", "readonly-result")), "isError", false);
                default -> Map.of();
            };
            byte[] body = JSON.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", req.get("id"), "result", result));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        return server;
    }

    @Test void realMcpDiscoveryOnlyWhenExplicitlyRequested() throws Exception {
        String command = System.getProperty("codex.smoke.command", "");
        org.junit.jupiter.api.Assumptions.assumeFalse(command.isBlank());
        var methods = new CopyOnWriteArrayList<String>();
        HttpServer server = startReadOnlyMcpStub(methods);
        try {
            Process p = new ProcessBuilder(command, "app-server", "--listen", "stdio://")
                    .directory(temp.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try (var rpc = new CodexAgentAdaptor.Rpc(p, 30)) {
                rpc.call("initialize", JSON.valueToTree(Map.of("clientInfo", Map.of("name", "gateway_mcp_check", "version", "1.0"),
                        "capabilities", Map.of("experimentalApi", true))));
                rpc.notify("initialized", JSON.createObjectNode());
                var params = JSON.createObjectNode().put("cwd", temp.toString()).put("sandbox", "read-only").put("approvalPolicy", "never");
                params.putObject("config").putObject("mcp_servers").putObject("client-tools")
                        .put("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp/smoke?endUserId=u&sessionId=s&chatId=c");
                String thread = rpc.call("thread/start", params).path("thread").path("id").asText();
                boolean ready = false;
                while (!ready) {
                    JsonNode notice = rpc.nextNotification();
                    if ("mcpServer/startupStatus/updated".equals(notice.path("method").asText())) {
                        System.out.println("MCP startup: name=" + notice.path("params").path("name").asText()
                                + " status=" + notice.path("params").path("status").asText());
                        ready = "client-tools".equals(notice.path("params").path("name").asText())
                                && "ready".equals(notice.path("params").path("status").asText());
                    }
                }
                JsonNode status = rpc.call("mcpServerStatus/list", JSON.createObjectNode().put("threadId", thread));
                assertTrue(status.toString().contains("read_only_test"), status.toString());
                JsonNode result = rpc.call("mcpServer/tool/call", JSON.createObjectNode().put("threadId", thread)
                        .put("server", "client-tools").put("tool", "read_only_test").set("arguments", JSON.createObjectNode()));
                assertTrue(result.toString().contains("readonly-result"), result.toString());
                assertTrue(methods.containsAll(List.of("initialize", "tools/list", "tools/call")));
            }
        } finally { server.stop(0); }
    }

    @Test void realMcpModelToolSmokeOnlyWhenExplicitlyRequested() throws Exception {
        String command = System.getProperty("codex.smoke.command", "");
        org.junit.jupiter.api.Assumptions.assumeFalse(command.isBlank());
        var methods = new CopyOnWriteArrayList<String>();
        HttpServer server = startReadOnlyMcpStub(methods);
        try {
            var adaptor = new CodexAgentAdaptor(command, System.getProperty("codex.smoke.model", ""),
                    System.getProperty("codex.smoke.model-provider", ""), "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp",
                    90, temp.toString(), "只使用指定的只读 MCP 工具，不要执行命令或读写文件。", sessions);
            var sink = new Sink();
            adaptor.chatStream(new AgentRequest("smoke", "readonly", "请调用 client-tools MCP 服务器的 read_only_test 工具一次，原样回复工具返回的文本。",
                    "这是只读 MCP 连通性测试。必须调用指定工具，禁止使用其他工具。", null, "mcp-model-smoke"), sink);
            assertNull(sink.error, sink.error);
            assertTrue(methods.contains("tools/call"), "Model did not call MCP tool; output=" + sink.output);
            assertTrue(sink.output.contains("readonly-result"));
            assertFalse(sink.deltas.isEmpty());
        } finally { server.stop(0); }
    }

    @Test void realTextSmokeOnlyWhenExplicitlyRequested() {
        String command = System.getProperty("codex.smoke.command", "");
        org.junit.jupiter.api.Assumptions.assumeFalse(command.isBlank());
        var adaptor = new CodexAgentAdaptor(command, System.getProperty("codex.smoke.model", ""),
                System.getProperty("codex.smoke.model-provider", ""), "", 90, temp.toString(), "只输出用户要求的文本，不调用工具。", sessions);
        var sink = new Sink();
        adaptor.chatStream(new AgentRequest("smoke", "readonly", "记住本会话口令 orchid17，仅回复 CODEX_OK", "", null, "smoke"), sink);
        assertNull(sink.error, sink.error);
        assertTrue(sink.output.contains("CODEX_OK"));
        assertFalse(sink.deltas.isEmpty());
        var continued = adaptor.chat(new AgentRequest("smoke", "readonly", "前一条让你记住的口令是什么？只回复口令。", "", sink.session, "smoke-resume"));
        assertEquals(sink.session, continued.sessionId());
        assertTrue(continued.output().contains("orchid17"));
    }

    /** A real subprocess with deterministic JSONL; no CLI auth/model calls. */
    public static class FakeCli {
        private static String mode; private static Path log;
        private static final BufferedReader IN = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        static JsonNode read() throws Exception {
            String line = IN.readLine(); if (line == null) throw new EOFException();
            Files.writeString(log, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return JSON.readTree(line);
        }
        static void send(Object value) throws Exception {
            var out = new OutputStreamWriter(System.out, StandardCharsets.UTF_8);
            out.write(JSON.writeValueAsString(value) + "\n"); out.flush();
        }
        static void reply(JsonNode request, Object value) throws Exception { send(Map.of("id", request.path("id").asInt(), "result", value)); }
        static void event(String method, Map<String, Object> extra) throws Exception {
            var p = new LinkedHashMap<String, Object>(Map.of("threadId", "thread-123", "turnId", "turn-123")); p.putAll(extra);
            send(Map.of("method", method, "params", p));
        }
        public static void main(String[] args) throws Exception {
            mode = args[0]; log = Path.of(args[1]);
            JsonNode init = read();
            if (mode.equals("timeout")) { Thread.sleep(30000); return; }
            if (mode.equals("exit")) return;
            if (mode.equals("rpc-error")) { send(Map.of("id", init.path("id").asInt(), "error", Map.of("code", -1, "message", "test RPC failure"))); return; }
            reply(init, Map.of("userAgent", "fake"));
            if (!read().path("method").asText().equals("initialized")) throw new IllegalStateException("missing initialized");
            JsonNode thread = read();
            reply(thread, Map.of("thread", Map.of("id", "thread-123")));
            if (thread.path("params").path("config").path("mcp_servers").has("client-tools")) {
                event("mcpServer/startupStatus/updated", Map.of("name", "client-tools", "status", "starting"));
                if (mode.equals("mcp-timeout")) { Thread.sleep(30000); return; }
                if (mode.equals("mcp-failed") || mode.equals("mcp-cancelled")) {
                    event("mcpServer/startupStatus/updated", Map.of("name", "client-tools", "status",
                            mode.equals("mcp-failed") ? "failed" : "cancelled", "error", "test MCP startup failure"));
                    IN.readLine(); return;
                }
                if (mode.equals("mcp-delayed")) {
                    send(Map.of("method", "mcpServer/startupStatus/updated", "params", Map.of("name", "client-tools", "status", "ready", "threadId", "different-thread")));
                    Thread.sleep(150);
                    if (IN.ready()) throw new IllegalStateException("RPC sent before current thread MCP readiness");
                }
                event("mcpServer/startupStatus/updated", Map.of("name", "client-tools", "status", "ready"));
                JsonNode inventory = read();
                if (!inventory.path("method").asText().equals("mcpServerStatus/list"))
                    throw new IllegalStateException("turn started before MCP inventory discovery");
                if (mode.equals("mcp-list-error")) {
                    send(Map.of("id", inventory.path("id").asInt(), "error", Map.of("code", -1, "message", "test inventory failure")));
                    IN.readLine(); return;
                }
                reply(inventory, Map.of("data", List.of(Map.of("name", "client-tools", "tools", Map.of()))));
            }
            JsonNode turn = read();
            if (mode.equals("empty")) {
                reply(turn, Map.of("turn", Map.of("id", "turn-123", "status", "inProgress")));
                event("turn/completed", Map.of("turn", Map.of("id", "turn-123", "status", "completed")));
                IN.readLine(); return;
            }
            // Event before response exercises buffering and response correlation.
            if (!mode.equals("aggregate")) event("item/agentMessage/delta", Map.of("itemId", "item-1", "delta", "你"));
            reply(turn, Map.of("turn", Map.of("id", "turn-123", "status", "inProgress")));
            if (mode.equals("error-event") || mode.equals("retry")) {
                event("error", Map.of("willRetry", mode.equals("retry"), "error", Map.of("message", "test error notification")));
                if (mode.equals("error-event")) { IN.readLine(); return; }
            }
            if (mode.equals("approval")) {
                for (String method : List.of("item/commandExecution/requestApproval", "mcpServer/elicitation/request", "item/tool/requestUserInput")) {
                    send(Map.of("id", "server-1", "method", method, "params", Map.of())); read();
                }
            }
            if (mode.equals("mcp")) {
                String url = thread.path("params").path("config").path("mcp_servers").path("client-tools").path("url").asText();
                for (String method : List.of("initialize", "tools/list", "tools/call")) {
                    String body = JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", method,
                            "params", Map.of("name", "read_only_test", "arguments", Map.of())));
                    var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url))
                            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() != 200) throw new IllegalStateException("MCP failed");
                }
            }
            // Foreign turn notification must never leak to this answer.
            send(Map.of("method", "item/agentMessage/delta", "params", Map.of("threadId", "other", "turnId", "other", "delta", "wrong")));
            if (!mode.equals("aggregate")) event("item/agentMessage/delta", Map.of("itemId", "item-1", "delta", "好"));
            event("item/completed", Map.of("item", Map.of("id", "item-1", "type", "agentMessage", "text", "你好")));
            event("turn/completed", Map.of("turn", Map.of("id", "turn-123", "status", mode.equals("failed") ? "failed" : "completed", "error", Map.of("message", "test failed turn"))));
            // Adapter must end the long-lived server after completion rather than await exit.
            IN.readLine();
            Files.writeString(log, "{\"eof\":true}\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        }
    }
}
