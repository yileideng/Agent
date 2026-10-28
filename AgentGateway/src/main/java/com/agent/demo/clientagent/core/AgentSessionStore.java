package com.agent.demo.clientagent.core;

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

    /** 未指定 clientId 时使用的默认客户端。 */
    private static final String DEFAULT_CLIENT = "default";

    /**
     * 单个会话的状态：中间件持有，Agent 侧会话ID只作为加速续接的引用。
     */
    public static final class SessionState {
        /** 中间件侧的会话 ID（带 clientId:endUserId 前缀）。 */
        private final String sessionId;
        /** Agent 侧会话 ID：CLI 的 session_id / 扣子的 conversationId。 */
        private volatile String externalSessionId;
        /** 最后一次访问时间，用于 TTL 清理。 */
        private volatile long lastAccessAt;

        private SessionState(String sessionId) {
            this.sessionId = sessionId;
            this.lastAccessAt = System.currentTimeMillis();
        }

        public String sessionId() {
            touch();
            return sessionId;
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

    /** 会话键 (adaptor|clientId|endUserId|sessionId) -> 会话状态。 */
    private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();
    /** 会话空闲多久后过期。 */
    private final long ttlMillis;

    public AgentSessionStore(@Value("${agent.session.ttl-minutes:120}") long ttlMinutes) {
        this.ttlMillis = Duration.ofMinutes(ttlMinutes).toMillis();
    }

    /**
     * 生成/规范化会话ID：为空时新建，非空时补上 (clientId, endUserId) 前缀，
     * 保证不同客户端、不同终端用户即使传相同 sessionId 也不会串号。
     */
    public String resolveSessionId(String clientId, String endUserId, String sessionId) {
        String scope = scope(clientId) + ":" + scope(endUserId);
        if (sessionId == null || sessionId.isBlank()) {
            return scope + ":" + UUID.randomUUID();
        }
        String prefix = scope + ":";
        return sessionId.startsWith(prefix) ? sessionId : prefix + sessionId;
    }

    /**
     * 按 (adaptor, clientId, endUserId, sessionId) 取会话状态，不存在则创建。
     */
    public SessionState getOrCreate(String adaptor, String clientId, String endUserId, String sessionId) {
        SessionState state = sessions.computeIfAbsent(
                key(adaptor, clientId, endUserId, sessionId),
                ignored -> new SessionState(sessionId));
        state.touch();
        return state;
    }

    /**
     * 读取该 Agent 现有的原生会话 ID，不创建状态。
     *
     * <p>返回 {@code null} 表示进程内还没有可复用的原生会话（首次请求、重启后或已失效），
     * 调用方应改用共享会话历史重建上下文。
     */
    public String nativeSessionId(String adaptor, String clientId, String endUserId, String sessionId) {
        SessionState state = sessions.get(key(adaptor, clientId, endUserId, sessionId));
        if (state == null) {
            return null;
        }
        state.touch();
        return state.externalSessionId();
    }

    /**
     * 让该 Agent 的原生会话引用失效，但不删除共享会话历史。
     *
     * <p>本轮失败后调用：原生会话可能已经处于不一致状态（例如 turn 中途退出），
     * 下一轮必须按共享历史重建，而不是继续追加到不可信的线程。
     */
    public void clearNativeSession(String adaptor, String clientId, String endUserId, String sessionId) {
        SessionState state = sessions.get(key(adaptor, clientId, endUserId, sessionId));
        if (state != null) {
            state.bindExternalSessionId(null);
        }
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

    private String key(String adaptor, String clientId, String endUserId, String sessionId) {
        return adaptor + "|" + scope(clientId) + "|" + scope(endUserId) + "|" + sessionId;
    }

    private String scope(String clientId) {
        return clientId == null || clientId.isBlank() ? DEFAULT_CLIENT : clientId;
    }
}
