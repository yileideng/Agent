package com.agent.demo.agentclient.controller;

import com.agent.demo.agentclient.client.Client;
import com.agent.demo.agentclient.client.MobileClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 自研客户端的用户入口：接收用户输入后转发给中间件，由中间件调度 Agent。
 */
@RestController
public class ClientChatController {

    /** SERVER 示例客户端（工具走 HTTP 回调）。 */
    private final Client client;
    /** MOBILE 示例客户端（工具走 WebSocket）。 */
    private final MobileClient mobileClient;

    public ClientChatController(Client client, MobileClient mobileClient) {
        this.client = client;
        this.mobileClient = mobileClient;
    }

    /**
     * GET /chat
     *
     * <p>作用：示例 SERVER 客户端的用户入口。内部以 CAP 信封调用中间件
     * {@code POST /chat}，工具调用由中间件通过 HTTP 回调回来执行。
     *
     * <p>入参：
     * <ul>
     *   <li>{@code prompt}：用户输入，必填；</li>
     *   <li>{@code agent}：可选，self / cli / coze，缺省用中间件默认适配器；</li>
     *   <li>{@code sessionId}：可选，传上一轮 final 里的 sessionId 续聊。</li>
     * </ul>
     *
     * <p>返回：中间件的 {@code final} 信封 JSON，payload 含
     * {@code sessionId}、{@code chatId}、{@code agent}、{@code output}。
     */
    @GetMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public String chat(@RequestParam String prompt,
                       @RequestParam(required = false) String agent,
                       @RequestParam(required = false) String sessionId) {
        return client.chat(prompt, agent, sessionId);
    }

    /**
     * GET /mobile/chat
     *
     * <p>作用：示例 MOBILE 客户端的用户入口。客户端通过 WebSocket 长连接接收工具调用，
     * 对话仍以 CAP 信封调用中间件 {@code POST /chat}。
     *
     * <p>入参：
     * <ul>
     *   <li>{@code prompt}：用户输入，必填；</li>
     *   <li>{@code agent}：可选，self / cli / coze；</li>
     *   <li>{@code sessionId}：可选，续聊会话 ID。</li>
     * </ul>
     *
     * <p>返回：中间件的 {@code final} 信封 JSON，payload 含
     * {@code sessionId}、{@code chatId}、{@code agent}、{@code output}。
     */
    @GetMapping(value = "/mobile/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public String mobileChat(@RequestParam String prompt,
                             @RequestParam(required = false) String agent,
                             @RequestParam(required = false) String sessionId) {
        return mobileClient.chat(prompt, agent, sessionId);
    }

    /**
     * GET /mobile/status
     *
     * <p>作用：查看 MOBILE 客户端到中间件的 WebSocket 是否已连接，便于排障。
     *
     * <p>入参：无。
     *
     * <p>返回 200：{@code {"connected": boolean}}。
     */
    @GetMapping(value = "/mobile/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> mobileStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("connected", mobileClient.isConnected());
        return status;
    }
}
