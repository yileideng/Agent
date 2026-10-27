package com.agent.demo.clientagent.controller.client;

import com.agent.demo.clientagent.acp_agent.AcpAgent;
import com.agent.demo.clientagent.acp_agent.AgentRouter;
import com.agent.demo.clientagent.core.AgentRequest;
import com.agent.demo.clientagent.core.AgentResponse;
import com.agent.demo.clientagent.core.AgentStreamSink;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 客户端 → 中间件的对话入口。
 *
 * <p>同一个接口支持两种响应：
 * <ul>
 *   <li>{@code Accept: application/json}：同步返回 final 信封；</li>
 *   <li>{@code Accept: text/event-stream}：SSE 推送 delta / thinking / final / error。</li>
 * </ul>
 */
@RestController
public class ChatController {

    /**
     * CAP 请求信封。
     *
     * @param v       协议版本，固定 "0.1"
     * @param id      请求 ID，响应原样带回
     * @param type    消息类型，固定 "chat"
     * @param payload 业务字段
     */
    public record ChatEnvelope(String v, String id, String type, ChatPayload payload) {
    }

    /**
     * chat 请求的业务字段。
     *
     * @param clientId     客户端标识；为空时用网关默认客户端
     * @param endUserId    终端用户标识；为空时用 "default"
     * @param sessionId    续聊会话 ID；为空表示新会话
     * @param prompt       用户输入，必填
     * @param agent        指定 Agent（self/cli/coze）；为空用默认适配器
     * @param clientPrompt 本轮覆盖的客户端系统提示词；可为空
     * @param context      页面/选中内容等上下文；当前版本保留
     */
    public record ChatPayload(String clientId,
                              String endUserId,
                              String sessionId,
                              String prompt,
                              String agent,
                              String clientPrompt,
                              Map<String, Object> context) {
    }

    /**
     * 成功响应：final 信封。
     *
     * @param v       协议版本
     * @param id      对应请求的 ID
     * @param type    固定 "final"
     * @param payload 本轮结果
     */
    public record FinalEnvelope(String v, String id, String type, FinalPayload payload) {
    }

    /**
     * final 的业务字段。
     *
     * @param sessionId 下一轮续聊凭证
     * @param chatId    本轮对话 ID，用于关联工具调用
     * @param agent     实际使用的 Agent
     * @param output    Agent 最终回答
     */
    public record FinalPayload(String sessionId, String chatId, String agent, String output) {
    }

    /**
     * 流式响应：delta 信封。
     *
     * @param v       协议版本
     * @param id      对应请求 ID
     * @param type    固定 "delta"
     * @param payload 文本增量
     */
    public record DeltaEnvelope(String v, String id, String type, DeltaPayload payload) {
    }

    /**
     * delta 的业务字段。
     *
     * @param chatId 本轮对话 ID
     * @param seq    本轮流内递增序号，delta/thinking 共用
     * @param text   文本增量
     */
    public record DeltaPayload(String chatId, long seq, String text) {
    }

    /**
     * 流式响应：thinking 信封。
     *
     * @param v       协议版本
     * @param id      对应请求 ID
     * @param type    固定 "thinking"
     * @param payload 思考增量
     */
    public record ThinkingEnvelope(String v, String id, String type, ThinkingPayload payload) {
    }

    /**
     * thinking 的业务字段。
     *
     * @param chatId 本轮对话 ID
     * @param seq    与 delta 共用的递增序号
     * @param text   思考增量
     */
    public record ThinkingPayload(String chatId, long seq, String text) {
    }

    /**
     * 失败响应：error 信封。
     *
     * @param v       协议版本
     * @param id      对应请求的 ID；无法解析时可为 null
     * @param type    固定 "error"
     * @param payload 错误详情
     */
    public record ErrorEnvelope(String v, String id, String type, ErrorPayload payload) {
    }

    /**
     * error 的业务字段。
     *
     * @param code    错误码，如 INVALID_REQUEST / AGENT_ERROR
     * @param message 可读错误信息
     */
    public record ErrorPayload(String code, String message) {
    }

    /** Agent 路由层：按名称解析并校验可用性。 */
    private final AgentRouter agentRouter;
    /** 请求未带 clientId 时使用的默认客户端。 */
    private final String defaultClientId;

    /**
     * 流式任务线程池。
     *
     * <p>必须在返回 SseEmitter 之后异步执行 Agent，否则 SseEmitter 还没交给 Spring，
     * 期间发送的 delta 会被缓冲，等整轮结束才一次性 flush，客户端就看不出流式效果。
     */
    private final ExecutorService streamExecutor = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable, "chat-stream");
        thread.setDaemon(true);
        return thread;
    });

    public ChatController(AgentRouter agentRouter,
                          @Value("${agent.default-client-id:agent-client}") String defaultClientId) {
        this.agentRouter = agentRouter;
        this.defaultClientId = defaultClientId;
    }

    /**
     * POST /chat
     *
     * <p>作用：客户端发起一轮对话；网关选择 Agent、执行 Agent Loop，
     * 期间产生的工具调用会通过 HTTP 回调或 WebSocket 下发回客户端。
     *
     * <p>入参：{@link ChatEnvelope}，字段含义：
     * <ul>
     *   <li>{@code v}：协议版本，固定 {@code "0.1"}；</li>
     *   <li>{@code id}：请求 ID，响应原样带回；</li>
     *   <li>{@code type}：固定 {@code "chat"}；</li>
     *   <li>{@code payload.clientId}：客户端标识，缺省用 {@code agent.default-client-id}；</li>
     *   <li>{@code payload.endUserId}：终端用户标识，缺省 {@code "default"}；</li>
     *   <li>{@code payload.prompt}：用户输入，必填；</li>
     *   <li>{@code payload.sessionId}：可选，传上一轮 final 里的 sessionId 续聊；</li>
     *   <li>{@code payload.agent}：可选，self / cli / coze；缺省用默认适配器；</li>
     *   <li>{@code payload.clientPrompt}：可选，本轮覆盖客户端系统提示词；</li>
     *   <li>{@code payload.context}：可选，页面/选中内容等上下文，当前版本保留。</li>
     * </ul>
     *
     * <p>返回：
     * <ul>
     *   <li>{@code Accept: application/json}：200 {@link FinalEnvelope}；</li>
     *   <li>{@code Accept: text/event-stream}：SSE，依次推送
     *       {@code delta} / {@code thinking} / {@code final} / {@code error}；</li>
     *   <li>参数错误：400 {@link ErrorEnvelope}。</li>
     * </ul>
     */
    @PostMapping(value = "/chat",
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public Object chat(@RequestBody ChatEnvelope request,
                       @RequestHeader(value = "Accept", required = false) String accept) {
        String id = request.id();
        if (!"chat".equals(request.type())) {
            return error(id, "INVALID_REQUEST", "type 必须是 chat");
        }
        ChatPayload payload = request.payload();
        if (payload == null || payload.prompt() == null || payload.prompt().isBlank()) {
            return error(id, "INVALID_REQUEST", "prompt 不能为空");
        }

        String clientId = text(payload.clientId(), defaultClientId);
        String endUserId = text(payload.endUserId(), "default");
        String sessionId = text(payload.sessionId(), null);
        String agent = text(payload.agent(), null);
        String clientPrompt = text(payload.clientPrompt(), null);
        String chatId = UUID.randomUUID().toString();
        String responseId = id == null || id.isBlank() ? chatId : id;

        AcpAgent adaptor;
        try {
            adaptor = agentRouter.resolve(agent);
        } catch (Exception e) {
            return error(id, "AGENT_UNAVAILABLE", e.getMessage());
        }

        AgentRequest agentRequest = new AgentRequest(
                clientId, endUserId, payload.prompt(), clientPrompt, sessionId, chatId);

        if (accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            return stream(adaptor, agentRequest, chatId, responseId);
        }

        try {
            AgentResponse result = agentRouter.chat(agentRequest, adaptor.name());
            return ResponseEntity.ok(new FinalEnvelope("0.1", responseId, "final",
                    new FinalPayload(result.sessionId(), chatId, adaptor.name(), result.output())));
        } catch (Exception e) {
            return error(id, "AGENT_ERROR", e.getMessage());
        }
    }

    /**
     * 把适配器的流式回调转成 CAP SSE。
     */
    private SseEmitter stream(AcpAgent adaptor,
                              AgentRequest request,
                              String chatId,
                              String responseId) {
        SseEmitter emitter = new SseEmitter(300_000L);
        emitter.onTimeout(emitter::complete);
        emitter.onError(throwable -> emitter.complete());
        AtomicLong seq = new AtomicLong();

        AgentStreamSink sink = new AgentStreamSink() {
            @Override
            public void delta(String text) {
                if (text == null || text.isEmpty()) {
                    return;
                }
                send(emitter, "delta", new DeltaEnvelope("0.1", responseId, "delta",
                        new DeltaPayload(chatId, seq.incrementAndGet(), text)));
            }

            @Override
            public void thinking(String text) {
                if (text == null || text.isEmpty()) {
                    return;
                }
                send(emitter, "thinking", new ThinkingEnvelope("0.1", responseId, "thinking",
                        new ThinkingPayload(chatId, seq.incrementAndGet(), text)));
            }

            @Override
            public void finish(String sessionId, String output) {
                send(emitter, "final", new FinalEnvelope("0.1", responseId, "final",
                        new FinalPayload(sessionId, chatId, adaptor.name(), output)));
                emitter.complete();
            }

            @Override
            public void error(String code, String message) {
                send(emitter, "error", new ErrorEnvelope("0.1", responseId, "error",
                        new ErrorPayload(code, message == null ? "" : message)));
                emitter.complete();
            }
        };

        try {
            streamExecutor.execute(() -> {
                try {
                    agentRouter.stream(request, adaptor.name(), sink);
                } catch (Exception e) {
                    sink.error("AGENT_ERROR", e.getMessage());
                }
            });
        } catch (Exception e) {
            sink.error("AGENT_ERROR", "流式任务提交失败: " + e.getMessage());
        }
        return emitter;
    }

    @PreDestroy
    public void shutdown() {
        streamExecutor.shutdownNow();
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private ResponseEntity<ErrorEnvelope> error(String id, String code, String message) {
        return ResponseEntity.badRequest().body(new ErrorEnvelope(
                "0.1", id, "error", new ErrorPayload(code, message == null ? "" : message)));
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
