package com.agent.demo.clientagent.controller;

import com.agent.demo.clientagent.agent_brain.ChatResult;
import com.agent.demo.clientagent.client.Client;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class ChatController {

    private final Client client;

    public ChatController(Client client) {
        this.client = client;
    }

    /**
     * 测试入口：GET /chat?prompt=我叫什么名字&agent=mock|coze|cli
     * agent 不传时使用配置中的默认适配器
     */
    @GetMapping("/chat")
    public Map<String, String> chat(@RequestParam String prompt,
                                    @RequestParam(required = false) String agent) {
        ChatResult result = client.chat(prompt, agent);
        return Map.of(
                "agent", agent == null ? "default" : agent,
                "sessionId", result.getBrainSession().getSessionId(),
                "output", result.getOutput());
    }
}
