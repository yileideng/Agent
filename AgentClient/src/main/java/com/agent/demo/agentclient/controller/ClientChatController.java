package com.agent.demo.agentclient.controller;

import com.agent.demo.agentclient.client.Client;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 自研客户端的用户入口：接收用户输入后转发给中间件，由中间件调度 Agent。
 */
@RestController
public class ClientChatController {

    private final Client client;

    public ClientChatController(Client client) {
        this.client = client;
    }

    /**
     * GET /chat
     * 客户端侧对话入口，返回中间件的原始响应（含 clientId、agent、sessionId、output）。
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
}
