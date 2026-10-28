package com.agent.demo.clientagent.core.conversation;

/**
 * 共享会话的隔离键。
 *
 * <p>同一个 {@code sessionId} 在不同客户端、不同终端用户下必须是不同会话，
 * 因此三者共同构成键。{@code sessionId} 使用规范化后的公共 CAP 会话 ID。
 *
 * @param clientId  客户端（租户）标识
 * @param endUserId 终端用户标识
 * @param sessionId 公共会话 ID
 */
public record ConversationKey(String clientId, String endUserId, String sessionId) {

    /**
     * 稳定的内部键，用于进程内映射；不写入磁盘文件名。
     */
    public String id() {
        return clientId + '\u0000' + endUserId + '\u0000' + sessionId;
    }
}
