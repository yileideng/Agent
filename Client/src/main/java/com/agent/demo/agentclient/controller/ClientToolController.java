package com.agent.demo.agentclient.controller;

import com.agent.demo.agentclient.client.Client;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * SERVER 客户端的 HTTP 回调端点。
 *
 * <p>只做一件事：把请求体交给 SDK，由 SDK 完成 tool_call 解析、ToolHandler 执行和
 * tool_result 信封生成。HTTP 与 WebSocket 两种传输在这里对齐。
 */
@RestController
public class ClientToolController {

    /** 持有本地工具和 SDK 的示例客户端。 */
    private final Client client;

    public ClientToolController(Client client) {
        this.client = client;
    }

    /**
     * POST /client/tools/execute
     *
     * <p>作用：接收中间件下发的工具调用，执行本地 ToolHandler 并回传结果。
     *
     * <p>入参：{@code tool_call} 信封，payload 含
     * {@code clientId}、{@code endUserId}、{@code sessionId}、{@code chatId}、
     * {@code callId}、{@code tool}、{@code arguments}、{@code deadlineMs}。
     *
     * <p>返回 200：{@code tool_result} 信封，payload 含
     * {@code chatId}、{@code callId}、{@code status=success|error}、
     * {@code result} 或 {@code error}。
     */
    @PostMapping(value = "/client/tools/execute", produces = MediaType.APPLICATION_JSON_VALUE)
    public String execute(@RequestBody String body) {
        return client.handleToolCall(body);
    }
}
