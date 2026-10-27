package com.agent.demo.acpagentserver.ws;

import com.agent.demo.acpagentserver.qwen.QwenAcpProcess;
import com.agent.demo.acpagentserver.qwen.QwenAcpProcessFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ACP WebSocket 入口：一个 WS 连接对应一个本地 {@code qwen --acp} 进程。
 *
 * <p>本模块只做透明代理：WS text frame ↔ Qwen stdin/stdout 的 JSON-RPC 行。
 */
@Component
public class AcpWebSocketHandler extends TextWebSocketHandler {

    private final QwenAcpProcessFactory processFactory;
    private final Map<String, QwenAcpProcess> processes = new ConcurrentHashMap<>();

    public AcpWebSocketHandler(QwenAcpProcessFactory processFactory) {
        this.processFactory = processFactory;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        try {
            QwenAcpProcess process = processFactory.start(
                    message -> sendToClient(session, message),
                    error -> {
                        System.out.println("[AcpAgentServer] " + session.getId() + " " + error);
                        closeQuietly(session, CloseStatus.SERVER_ERROR);
                    });
            processes.put(session.getId(), process);
            System.out.println("[AcpAgentServer] 连接 " + session.getId() + " 已启动 Qwen ACP");
        } catch (Exception e) {
            System.out.println("[AcpAgentServer] 启动 Qwen ACP 失败: " + e.getMessage());
            session.close(CloseStatus.SERVER_ERROR.withReason("Qwen ACP start failed"));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        QwenAcpProcess process = processes.get(session.getId());
        if (process == null || !process.isAlive()) {
            closeQuietly(session, CloseStatus.SERVER_ERROR);
            return;
        }
        try {
            process.send(message.getPayload());
        } catch (Exception e) {
            System.out.println("[AcpAgentServer] 写入 Qwen 失败: " + e.getMessage());
            closeQuietly(session, CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        QwenAcpProcess process = processes.remove(session.getId());
        if (process != null) {
            process.close();
        }
        System.out.println("[AcpAgentServer] 连接 " + session.getId() + " 已关闭: " + status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        System.out.println("[AcpAgentServer] 传输错误 " + session.getId() + ": " + exception.getMessage());
        closeQuietly(session, CloseStatus.SERVER_ERROR);
    }

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
}
