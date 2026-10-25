package com.agent.demo.clientagent.agent_adaptor;

import com.agent.demo.clientagent.core.AgentSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 会话状态仓库：由适配器层统一持有，Agent Brain 可替换而会话不丢失。
 * 会话按 (adaptor, clientId, sessionId) 隔离，clientId 就是后续多租户的隔离键。
 * 当前用内存实现，落库/Redis 时只需替换本类实现。
 */
@Component
public class AgentSessionStore {

    private static final String DEFAULT_CLIENT = "default";

    /**
     * 单个会话的状态：中间件持有，Agent 侧会话ID只作为加速续接的引用
     */
    public static final class SessionState {
        private final AgentSession agentSession;
        private volatile String externalSessionId;
        private volatile long lastAccessAt;

        private SessionState(String sessionId) {
            this.agentSession = new AgentSession().initSession(sessionId);
            this.lastAccessAt = System.currentTimeMillis();
        }

        public AgentSession brainSession() {
            touch();
            return agentSession;
        }

        /**
         * Agent 侧的会话ID（CLI 的 session_id、扣子的 conversationId 等）
         */
        public String externalSessionId() {
            touch();
            return externalSessionId;
        }

        public void bindExternalSessionId(String externalSessionId) {
            this.externalSessionId = externalSessionId;
            touch();
        }

        public long lastAccessAt() {
            return lastAccessAt;
        }

        void touch() {
            this.lastAccessAt = System.currentTimeMillis();
        }
    }

    private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public AgentSessionStore(@Value("${agent.session.ttl-minutes:120}") long ttlMinutes) {
        this.ttlMillis = Duration.ofMinutes(ttlMinutes).toMillis();
    }

    /**
     * 生成/规范化会话ID：为空时新建，非空时补上客户端前缀，保证不同客户端即使传相同 sessionId 也不会串号。
     */
    public String resolveSessionId(String clientId, String sessionId) {
        String scope = scope(clientId);
        if (sessionId == null || sessionId.isBlank()) {
            return scope + ":" + UUID.randomUUID();
        }
        String prefix = scope + ":";
        return sessionId.startsWith(prefix) ? sessionId : prefix + sessionId;
    }

    public SessionState getOrCreate(String adaptor, String clientId, String sessionId) {
        SessionState state = sessions.computeIfAbsent(key(adaptor, clientId, sessionId),
                ignored -> new SessionState(sessionId));
        state.touch();
        return state;
    }

    public void remove(String adaptor, String clientId, String sessionId) {
        sessions.remove(key(adaptor, clientId, sessionId));
    }

    public int size() {
        return sessions.size();
    }

    /**
     * 定期清理过期会话；TTL 与清理间隔均可配置
     */
    @Scheduled(fixedDelayString = "${agent.session.cleanup-interval-ms:600000}")
    public void evictExpired() {
        long deadline = System.currentTimeMillis() - ttlMillis;
        int before = sessions.size();
        sessions.entrySet().removeIf(entry -> entry.getValue().lastAccessAt() < deadline);
        int removed = before - sessions.size();
        if (removed > 0) {
            System.out.println("[SessionStore] 清理过期会话 " + removed + " 个，剩余 " + sessions.size());
        }
    }

    private String key(String adaptor, String clientId, String sessionId) {
        return adaptor + "|" + scope(clientId) + "|" + sessionId;
    }

    private String scope(String clientId) {
        return clientId == null || clientId.isBlank() ? DEFAULT_CLIENT : clientId;
    }
}
