package com.agent.demo.clientagent.acp_agent.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * ACP JSON-RPC 连接抽象。
 *
 * <p>{@link StdioAcpConnection} 负责本地子进程；{@link WsAcpConnection} 负责
 * 远程 ACP Server。会话、initialize、prompt 等语义由 {@link AcpSession} 复用。
 */
public interface AcpConnection extends AutoCloseable {

    /**
     * 建立连接；stdio 使用 cwd 作为子进程工作目录，WS 忽略该参数。
     */
    void start(Path cwd);

    boolean isAlive();

    void onNotification(Consumer<JsonNode> handler);

    void onRequest(Consumer<JsonNode> handler);

    JsonNode request(String method, Object params, long timeoutMs);

    void respond(Object id, Object result);

    void respondError(Object id, int code, String message);

    @Override
    void close();
}