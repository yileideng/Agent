package com.agent.demo.clientagent.controller;

import com.agent.demo.clientagent.agent_brain.ChatResult;
import com.agent.demo.clientagent.client.Client;
import com.agent.demo.clientagent.agent_adaptor.AgentAdaptorRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class ChatController {

    private final Client client;
    private final AgentAdaptorRegistry agentAdaptorRegistry;

    public ChatController(Client client, AgentAdaptorRegistry agentAdaptorRegistry) {
        this.client = client;
        this.agentAdaptorRegistry = agentAdaptorRegistry;
    }

    /**
     * 测试入口：GET /chat?prompt=我叫什么名字&agent=mock|coze|cli&sessionId=xxx
     * - agent 不传时使用配置中的默认适配器
     * - sessionId 不传表示新会话，返回后带上它即可多轮对话
     */
    @GetMapping("/chat")
    public Map<String, Object> chat(@RequestParam String prompt,
                                    @RequestParam(required = false) String agent,
                                    @RequestParam(required = false) String sessionId) {
        ChatResult result = client.chat(prompt, agent, sessionId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("clientId", client.getClientId());
        response.put("agent", agent == null ? agentAdaptorRegistry.defaultAdaptor() : agent);
        response.put("sessionId", result.getBrainSession().getSessionId());
        response.put("output", result.getOutput());
        return response;
    }
}
