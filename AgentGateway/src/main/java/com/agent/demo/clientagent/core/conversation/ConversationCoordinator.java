package com.agent.demo.clientagent.core.conversation;

import com.agent.demo.clientagent.acp_agent.AcpAgent;
import com.agent.demo.clientagent.acp_agent.AcpAgentRegistry;
import com.agent.demo.clientagent.acp_agent.AgentUnavailableException;
import com.agent.demo.clientagent.core.AgentRequest;
import com.agent.demo.clientagent.core.AgentResponse;
import com.agent.demo.clientagent.core.AgentSessionStore;
import com.agent.demo.clientagent.core.AgentStreamSink;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 跨 Agent 共享会话的协调器。
 *
 * <p>负责把"选哪个 Agent"和"这个会话之前发生了什么"分开：
 * <ul>
 *   <li>公共 ACP 会话 ID 只有一个，切换 Agent 不换会话；</li>
 *   <li>每个 Agent 仍持有自己的原生会话（CLI session / Codex thread），只做续接加速；</li>
 *   <li>共享历史持久化在 {@link ConversationStore}，成功后才推进该 Agent 的同步游标；</li>
 *   <li>本轮失败会失效该 Agent 的原生引用，下一轮按共享历史重建。</li>
 * </ul>
 *
 * <p>同一会话的整轮执行串行（{@link ConversationStore#turnLock}），但写历史只持短锁，
 * 因此模型执行期间的工具回调可以正常落库，不会与等待工具结果的模型互相阻塞。
 */
@Component
public class ConversationCoordinator {

    private final AcpAgentRegistry registry;
    private final ConversationStore store;
    private final ConversationContextRenderer renderer;
    private final AgentSessionStore sessions;

    public ConversationCoordinator(AcpAgentRegistry registry,
                                   ConversationStore store,
                                   ConversationContextRenderer renderer,
                                   AgentSessionStore sessions) {
        this.registry = registry;
        this.store = store;
        this.renderer = renderer;
        this.sessions = sessions;
    }

    /** 规范化公共会话 ID：空值新建，已有值补上客户端/用户前缀，保证不串号。 */
    public String canonicalSessionId(String clientId, String endUserId, String sessionId) {
        return sessions.resolveSessionId(clientId, endUserId, sessionId);
    }

    /** 解析并校验 Agent 可用性。 */
    public AcpAgent resolve(String agentId) {
        AcpAgent agent = registry.get(agentId);
        if (!agent.available()) {
            throw new AgentUnavailableException(agent.name(), agent.unavailableReason());
        }
        return agent;
    }

    /** 同步对话：同样经过共享历史，避免绕过协调器导致上下文缺失。 */
    public AgentResponse chat(AgentRequest request, String agentId) {
        AcpAgent agent = resolve(agentId);
        ConversationKey key = key(request);
        ReentrantLock turn = store.turnLock(key);
        turn.lock();
        try {
            AgentRequest derived = beginTurn(request, key, agent);
            try {
                AgentResponse response = agent.chat(derived);
                String output = response == null || response.output() == null ? "" : response.output();
                long seq = store.appendAssistant(key, request.chatId(), agent.name(), output);
                store.markSynced(key, agent.name(), seq);
                return new AgentResponse(key.sessionId(), output);
            } catch (RuntimeException e) {
                failTurn(key, request.chatId(), agent, "", "AGENT_ERROR", e.getMessage());
                throw e;
            }
        } finally {
            turn.unlock();
        }
    }

    /** 流式对话：delta 立即透传，成功时先落库再发送 final。 */
    public void stream(AgentRequest request, String agentId, AgentStreamSink sink) {
        AcpAgent agent;
        try {
            agent = resolve(agentId);
        } catch (RuntimeException e) {
            sink.error("AGENT_UNAVAILABLE", e.getMessage());
            return;
        }
        ConversationKey key = key(request);
        ReentrantLock turn = store.turnLock(key);
        turn.lock();
        try {
            AgentRequest derived = beginTurn(request, key, agent);
            StringBuilder answer = new StringBuilder();
            AtomicBoolean closed = new AtomicBoolean();
            AgentStreamSink wrapper = new AgentStreamSink() {
                @Override
                public void delta(String text) {
                    if (text != null && !text.isEmpty()) {
                        answer.append(text);
                    }
                    sink.delta(text);
                }

                @Override
                public void thinking(String text) {
                    sink.thinking(text);
                }

                @Override
                public void finish(String sessionId, String output) {
                    if (!closed.compareAndSet(false, true)) {
                        return;
                    }
                    String finalText = output == null || output.isEmpty() ? answer.toString() : output;
                    long seq = store.appendAssistant(key, request.chatId(), agent.name(), finalText);
                    store.markSynced(key, agent.name(), seq);
                    sink.finish(key.sessionId(), finalText);
                }

                @Override
                public void error(String code, String message) {
                    if (!closed.compareAndSet(false, true)) {
                        return;
                    }
                    failTurn(key, request.chatId(), agent, answer.toString(), code, message);
                    sink.error(code, message);
                }
            };
            agent.chatStream(derived, wrapper);
            if (!closed.get()) {
                // 适配器没有给出终止事件：按失败处理，不能让客户端等到超时还以为成功。
                String message = "Agent 未返回结束事件，本轮结果未知。";
                failTurn(key, request.chatId(), agent, answer.toString(), "AGENT_ERROR", message);
                sink.error("AGENT_ERROR", message);
            }
        } catch (RuntimeException e) {
            failTurn(key, request.chatId(), agent, "", "AGENT_ERROR", e.getMessage());
            sink.error("AGENT_ERROR", e.getMessage());
        } finally {
            turn.unlock();
        }
    }

    /**
     * 开始一轮：先落库用户输入，再按该 Agent 的游标拼接待补历史。
     */
    private AgentRequest beginTurn(AgentRequest request, ConversationKey key, AcpAgent agent) {
        String sessionId = key.sessionId();
        long userSeq = store.appendUser(key, request.chatId(), agent.name(), request.userPrompt());
        boolean resumable = sessions.nativeSessionId(
                agent.name(), key.clientId(), key.endUserId(), sessionId) != null;
        long cursor = resumable ? store.syncedThrough(key, agent.name()) : 0L;
        List<ConversationStore.Event> history = store.eventsBetween(key, cursor, userSeq);
        String prefix = renderer.render(history);
        String prompt = prefix.isEmpty()
                ? request.userPrompt()
                : prefix + "\n\n本轮用户输入：\n" + request.userPrompt();
        return new AgentRequest(request.clientId(), request.endUserId(), prompt,
                request.clientPrompt(), sessionId, request.chatId());
    }

    private void failTurn(ConversationKey key, String chatId, AcpAgent agent,
                          String partialOutput, String code, String message) {
        store.appendFailure(key, chatId, agent.name(), code, message, partialOutput);
        // 原生会话可能已被污染（部分输出、未知工具状态），下一轮必须按共享历史重建。
        sessions.clearNativeSession(agent.name(), key.clientId(), key.endUserId(), key.sessionId());
    }

    private ConversationKey key(AgentRequest request) {
        return new ConversationKey(
                request.clientId() == null || request.clientId().isBlank() ? "default" : request.clientId(),
                request.endUserId() == null || request.endUserId().isBlank() ? "default" : request.endUserId(),
                request.sessionId());
    }
}
