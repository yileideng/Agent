package com.agent.demo.clientagent.controller;

import com.agent.demo.clientagent.agent_adaptor.AgentAdaptor;
import com.agent.demo.clientagent.agent_adaptor.AgentAdaptorRegistry;
import com.agent.demo.clientagent.core.AgentRequest;
import com.agent.demo.clientagent.core.AgentResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 客户端 → 中间件的对话入口。
 *
 * <p>只接受 CAP v0.1 信封。请求/响应都用 Java record 映射，字段含义见
 * {@link ChatEnvelope} / {@link ChatPayload} / {@link FinalEnvelope}。
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
     * 失败响应：error 信封。
     *
     * @param v       协议版本
     * @param id      对应请求的 ID；无法解析时可为 null
     * @param type    固定 "error"
     * @param payload  错误详情
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

    /** Agent 适配器注册表，用于按名称选择 Agent。 */
    private final AgentAdaptorRegistry agentAdaptorRegistry;
    /** 请求未带 clientId 时使用的默认客户端。 */
    private final String defaultClientId;

    public ChatController(AgentAdaptorRegistry agentAdaptorRegistry,
                          @Value("${agent.default-client-id:agent-client}") String defaultClientId) {
        this.agentAdaptorRegistry = agentAdaptorRegistry;
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
     *   <li>200 {@link FinalEnvelope}：payload 含 sessionId/chatId/agent/output；</li>
     *   <li>400 {@link ErrorEnvelope}：payload 含 code/message。</li>
     * </ul>
     */
    @PostMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> chat(@RequestBody ChatEnvelope request) {
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

        try {
            AgentAdaptor adaptor = agentAdaptorRegistry.get(agent);
            AgentResponse result = adaptor.chat(new AgentRequest(
                    clientId, endUserId, payload.prompt(), clientPrompt, sessionId, chatId));

            String responseId = id == null || id.isBlank() ? chatId : id;
            return ResponseEntity.ok(new FinalEnvelope("0.1", responseId, "final",
                    new FinalPayload(result.sessionId(), chatId, adaptor.name(), result.output())));
        } catch (Exception e) {
            return error(id, "AGENT_ERROR", e.getMessage());
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
