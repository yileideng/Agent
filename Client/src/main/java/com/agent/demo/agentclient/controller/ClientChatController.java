package com.agent.demo.agentclient.controller;

import com.agent.demo.agentclient.client.Client;
import com.agent.demo.agentclient.mobile.MobileClient;
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

    private final Client client;
    private final MobileClient mobileClient;

    public ClientChatController(Client client, MobileClient mobileClient) {
        this.client = client;
        this.mobileClient = mobileClient;
    }

    /**
     * GET /chat
     * 客户端侧对话入口（SERVER 类型客户端，工具走 HTTP 回调），返回中间件的原始响应
     * （含 clientId、agent、sessionId、output）。
     * 参数：
     * - prompt：用户输入（必填）
     * - agent：指定中间件上的 Agent，缺省使用中间件默认适配器
     * - sessionId：续聊时传上一轮返回的 sessionId
     */
    @GetMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public String chat(@RequestParam String prompt,
                       @RequestParam(required = false) String agent,
                       @RequestParam(required = false) String sessionId) {
        return client.chat(prompt, agent, sessionId);
    }

    /**
     * GET /mobile/chat
     * 移动端客户端对话入口（MOBILE 类型客户端）：先用 WebSocket 长连接向中间件注册并保持连接，
     * 再转发 /chat，Agent 工具调用会经由 WS 推回本客户端执行，因此没有回调地址也能跑通。
     * 参数：
     * - prompt：用户输入（必填）
     * - agent：指定中间件上的 Agent，缺省使用中间件默认适配器
     * - sessionId：续聊时传上一轮返回的 sessionId
     * 返回：中间件的原始响应（含 clientId、agent、sessionId、output）
     */
    @GetMapping(value = "/mobile/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public String mobileChat(@RequestParam String prompt,
                             @RequestParam(required = false) String agent,
                             @RequestParam(required = false) String sessionId) {
        return mobileClient.chat(prompt, agent, sessionId);
    }

    /**
     * GET /mobile/status
     * 移动端客户端连接状态，便于排查 WebSocket 是否已连上中间件。
     */
    @GetMapping(value = "/mobile/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> mobileStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("connected", mobileClient.isConnected());
        return status;
    }
}
