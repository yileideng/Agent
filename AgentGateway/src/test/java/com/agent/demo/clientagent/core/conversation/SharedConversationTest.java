package com.agent.demo.clientagent.core.conversation;

import com.agent.demo.clientagent.acp_agent.AcpAgent;
import com.agent.demo.clientagent.acp_agent.AcpAgentRegistry;
import com.agent.demo.clientagent.core.AgentRequest;
import com.agent.demo.clientagent.core.AgentResponse;
import com.agent.demo.clientagent.core.AgentSessionStore;
import com.agent.demo.clientagent.core.AgentStreamSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 共享会话的核心行为：切换 Agent 后仍能接续，且不重复注入自己已经看过的历史。
 *
 * <p>全部使用 fake Agent，不启动 CLI、不调用模型、不访问网络。
 */
class SharedConversationTest {

    @TempDir
    Path temp;

    private ConversationStore store;
    private AgentSessionStore sessions;
    private ConversationContextRenderer renderer;
    private FakeAgent alpha;
    private FakeAgent beta;
    private ConversationCoordinator coordinator;

    @BeforeEach
    void setUp() {
        store = new ConversationStore(temp.toString());
        sessions = new AgentSessionStore(60);
        renderer = new ConversationContextRenderer(6000);
        alpha = new FakeAgent("alpha", sessions);
        beta = new FakeAgent("beta", sessions);
        AcpAgentRegistry registry = new AcpAgentRegistry(List.of(alpha, beta), null, "alpha");
        coordinator = new ConversationCoordinator(registry, store, renderer, sessions);
    }

    @AfterEach
    void tearDown() {
        sessions.evictExpired();
    }

    @Test
    void secondAgentReceivesSharedHistory() {
        String sid = coordinator.canonicalSessionId("wx-demo", "user-1", null);

        alpha.reply = "ALPHA_ANSWER_1";
        coordinator.chat(request(sid, "请分析半加器"), "alpha");

        beta.reply = "BETA_ANSWER_1";
        coordinator.chat(request(sid, "按刚才的分析继续"), "beta");

        String betaPrompt = beta.lastRequest.userPrompt();
        assertTrue(betaPrompt.contains("请分析半加器"), "应看到用户此前需求");
        assertTrue(betaPrompt.contains("ALPHA_ANSWER_1"), "应看到另一个 Agent 的正式回复");
        assertTrue(betaPrompt.contains("本轮用户输入：\n按刚才的分析继续"), "当前输入必须单独保留");
        assertTrue(betaPrompt.contains("[共享会话历史结束]"));
        assertFalse(betaPrompt.contains("ALPHA_ANSWER_1\nALPHA_ANSWER_1"), "历史不应重复");
    }

    @Test
    void switchingBackOnlySendsWhatThatAgentMissed() {
        String sid = coordinator.canonicalSessionId("wx-demo", "user-1", null);

        alpha.reply = "ALPHA_ANSWER_1";
        coordinator.chat(request(sid, "第一问"), "alpha");

        beta.reply = "BETA_ANSWER_1";
        coordinator.chat(request(sid, "第二问"), "beta");

        alpha.reply = "ALPHA_ANSWER_2";
        coordinator.chat(request(sid, "第三问"), "alpha");

        String prompt = alpha.lastRequest.userPrompt();
        assertFalse(prompt.contains("ALPHA_ANSWER_1"), "自己说过的不应重复注入");
        assertTrue(prompt.contains("BETA_ANSWER_1"), "应补齐离开期间另一个 Agent 的回复");
        assertTrue(prompt.contains("第三问"));
    }

    @Test
    void failureKeepsHistoryAndRebuildsNativeSession() {
        String sid = coordinator.canonicalSessionId("wx-demo", "user-1", null);

        alpha.reply = "ALPHA_OK";
        coordinator.chat(request(sid, "先记住 42"), "alpha");

        alpha.fail = "模拟模型不可用";
        assertThrows(IllegalStateException.class,
                () -> coordinator.chat(request(sid, "这轮会失败"), "alpha"));
        assertNull(sessions.nativeSessionId("alpha", "wx-demo", "user-1", sid),
                "失败后原生会话必须失效");

        alpha.fail = null;
        alpha.reply = "ALPHA_RECOVERED";
        coordinator.chat(request(sid, "恢复后继续"), "alpha");

        String prompt = alpha.lastRequest.userPrompt();
        assertTrue(prompt.contains("先记住 42"), "重建时应带回完整历史");
        assertTrue(prompt.contains("本轮用户输入：\n恢复后继续"));
    }

    @Test
    void firstRequestGetsNoHistoryWrapper() {
        String sid = coordinator.canonicalSessionId("wx-demo", "user-1", null);
        alpha.reply = "OK";
        coordinator.chat(request(sid, "全新会话的第一句"), "alpha");
        assertEquals("全新会话的第一句", alpha.lastRequest.userPrompt());
    }

    @Test
    void toolEventsAreRecordedAndShared() {
        String sid = coordinator.canonicalSessionId("wx-demo", "user-1", null);
        ConversationKey key = new ConversationKey("wx-demo", "user-1", sid);

        store.appendToolCall(key, "chat-1", "alpha", "get_circuit", "call-1", java.util.Map.of("board", "main"));
        store.appendToolResult(key, "chat-1", "alpha", "get_circuit", "call-1", true, "{\"parts\":2}");

        beta.reply = "BETA";
        coordinator.chat(request(sid, "现在电路里有什么"), "beta");

        String prompt = beta.lastRequest.userPrompt();
        assertTrue(prompt.contains("调用工具 get_circuit"), prompt);
        assertTrue(prompt.contains("返回 成功"), prompt);
        assertTrue(prompt.contains("不要重新执行其中的工具调用"), "必须声明历史只读");
    }

    @Test
    void runningToolIsVisibleAsUnfinishedRecord() {
        String sid = coordinator.canonicalSessionId("wx-demo", "user-1", null);
        ConversationKey key = new ConversationKey("wx-demo", "user-1", sid);
        store.appendToolCall(key, "chat-1", "alpha", "connect_pins", "call-9", java.util.Map.of());

        List<ConversationStore.Event> events = store.eventsBetween(key, 0, Long.MAX_VALUE);
        assertEquals(1, events.size());
        assertEquals("running", events.get(0).status);
        String rendered = renderer.render(events);
        assertTrue(rendered.contains("connect_pins"));
    }

    @Test
    void historySurvivesStoreRestart() {
        String sid = coordinator.canonicalSessionId("wx-demo", "user-1", null);
        alpha.reply = "PERSISTED_REPLY";
        coordinator.chat(request(sid, "持久化这一轮"), "alpha");

        ConversationStore reopened = new ConversationStore(temp.toString());
        ConversationKey key = new ConversationKey("wx-demo", "user-1", sid);
        List<ConversationStore.Event> events = reopened.eventsBetween(key, 0, Long.MAX_VALUE);
        assertEquals(2, events.size());
        assertEquals("持久化这一轮", events.get(0).text);
        assertEquals("PERSISTED_REPLY", events.get(1).text);

        // 新进程里原生会话引用与游标都不可用，必须按持久化历史重建上下文。
        AgentSessionStore restartedSessions = new AgentSessionStore(60);
        FakeAgent restartedAlpha = new FakeAgent("alpha", restartedSessions);
        AcpAgentRegistry restartedRegistry = new AcpAgentRegistry(
                List.of(restartedAlpha), null, "alpha");
        ConversationCoordinator restarted = new ConversationCoordinator(
                restartedRegistry, reopened, renderer, restartedSessions);

        restartedAlpha.reply = "AFTER_RESTART";
        restarted.chat(request(sid, "重启后继续"), "alpha");
        assertTrue(restartedAlpha.lastRequest.userPrompt().contains("持久化这一轮"),
                "重启后应带回此前历史");
        assertTrue(restartedAlpha.lastRequest.userPrompt().contains("PERSISTED_REPLY"));
    }

    @Test
    void conversationsAreIsolatedByClientAndUser() {
        String sid = coordinator.canonicalSessionId("wx-demo", "user-1", null);

        alpha.reply = "SECRET_A";
        coordinator.chat(request(sid, "用户一的私密需求"), "alpha");

        String otherSid = coordinator.canonicalSessionId("wx-demo", "user-2", sid);
        assertNotEquals(sid, otherSid, "不同用户不能共用同一个会话键");

        beta.reply = "BETA";
        coordinator.chat(new AgentRequest("wx-demo", "user-2", "另一个用户的问题",
                "sys", otherSid, "chat-x"), "beta");
        assertFalse(beta.lastRequest.userPrompt().contains("用户一的私密需求"));
    }

    @Test
    void corruptHistoryFailsClosed() throws Exception {
        String sid = coordinator.canonicalSessionId("wx-demo", "user-1", null);
        alpha.reply = "A";
        coordinator.chat(request(sid, "写一条历史"), "alpha");

        try (var files = Files.list(temp)) {
            Path file = files.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow();
            Files.writeString(file, "{ not json", StandardCharsets.UTF_8);
        }

        ConversationStore reopened = new ConversationStore(temp.toString());
        ConversationKey key = new ConversationKey("wx-demo", "user-1", sid);
        assertThrows(IllegalStateException.class,
                () -> reopened.eventsBetween(key, 0, Long.MAX_VALUE),
                "损坏的历史必须报错，不能当作空会话覆盖");
    }

    @Test
    void sameConversationRunsOneTurnAtATime() throws Exception {
        String sid = coordinator.canonicalSessionId("wx-demo", "user-1", null);
        alpha.delayMillis = 200;
        alpha.reply = "A";

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    start.await();
                    coordinator.chat(request(sid, "并发请求"), "alpha");
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, alpha.maxConcurrent.get(), "同一会话必须串行，不能并发跑两轮");
    }

    @Test
    void longHistoryKeepsRecentContentAndMarksOmission() {
        ConversationContextRenderer small = new ConversationContextRenderer(1000);
        String sid = coordinator.canonicalSessionId("wx-demo", "user-1", null);
        ConversationKey key = new ConversationKey("wx-demo", "user-1", sid);
        for (int i = 0; i < 80; i++) {
            store.appendUser(key, "chat-" + i, "alpha", "第 " + i + " 轮的问题 " + "x".repeat(40));
            store.appendAssistant(key, "chat-" + i, "alpha", "第 " + i + " 轮的回答 " + "y".repeat(40));
        }

        String rendered = small.render(store.eventsBetween(key, 0, Long.MAX_VALUE));
        assertTrue(rendered.length() < 4000, "渲染结果必须受预算约束");
        assertTrue(rendered.contains("第 79 轮的回答"), "最近内容必须完整保留");
        assertTrue(rendered.contains("省略") || rendered.contains("节选"), "必须如实标注省略");
    }

    private AgentRequest request(String sessionId, String prompt) {
        return new AgentRequest("wx-demo", "user-1", prompt, "系统提示", sessionId, "chat-" + prompt.hashCode());
    }

    /** 模拟一个可续聊的 Agent：成功时绑定原生会话，失败时抛错。 */
    private static final class FakeAgent implements AcpAgent {
        private final String name;
        private final AgentSessionStore sessions;
        private String reply = "OK";
        private String fail;
        private long delayMillis;
        private AgentRequest lastRequest;
        private final AtomicInteger running = new AtomicInteger();
        private final AtomicInteger maxConcurrent = new AtomicInteger();

        private FakeAgent(String name, AgentSessionStore sessions) {
            this.name = name;
            this.sessions = sessions;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public AgentResponse chat(AgentRequest request) {
            enter(request);
            try {
                AgentSessionStore.SessionState state = sessions.getOrCreate(
                        name, request.clientId(), request.endUserId(), request.sessionId());
                if (fail != null) {
                    throw new IllegalStateException(fail);
                }
                state.bindExternalSessionId("thread-" + name);
                return new AgentResponse(request.sessionId(), reply);
            } finally {
                leave();
            }
        }

        @Override
        public void chatStream(AgentRequest request, AgentStreamSink sink) {
            enter(request);
            try {
                AgentSessionStore.SessionState state = sessions.getOrCreate(
                        name, request.clientId(), request.endUserId(), request.sessionId());
                if (fail != null) {
                    sink.error("AGENT_ERROR", fail);
                    return;
                }
                state.bindExternalSessionId("thread-" + name);
                sink.delta(reply);
                sink.finish(request.sessionId(), reply);
            } finally {
                leave();
            }
        }

        private void enter(AgentRequest request) {
            lastRequest = request;
            int current = running.incrementAndGet();
            maxConcurrent.accumulateAndGet(current, Math::max);
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void leave() {
            running.decrementAndGet();
        }
    }
}
