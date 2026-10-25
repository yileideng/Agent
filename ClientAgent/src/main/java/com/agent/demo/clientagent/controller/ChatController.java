package com.agent.demo.clientagent.controller;

import com.agent.demo.clientagent.agent_adaptor.AgentAdaptor;
import com.agent.demo.clientagent.agent_adaptor.AgentAdaptorRegistry;
import com.agent.demo.clientagent.core.AgentRequest;
import com.agent.demo.clientagent.core.AgentResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端 → 中间件的对话入口。真正的客户端（如 AgentClient 模块）调用这里，
 * 由中间件选择 Agent、持有会话并路由客户端工具调用。
 */
@RestController
public class ChatController {

    private final AgentAdaptorRegistry agentAdaptorRegistry;
    private final String defaultClientId;

    public ChatController(AgentAdaptorRegistry agentAdaptorRegistry,
                          @Value("${agent.default-client-id:agent-client}") String defaultClientId) {
        this.agentAdaptorRegistry = agentAdaptorRegistry;
        this.defaultClientId = defaultClientId;
    }

    /**
     * GET /chat
     * 客户端发起一次对话，走完整的 客户端 → 中间件 → Agent → 客户端工具回调 链路。
     * 参数：
     * - prompt：用户输入（必填）
     * - agent：指定 self / coze / cli，缺省使用 agent.default-adaptor
     * - clientId：发起调用的客户端，缺省使用 agent.default-client-id
     * - sessionId：续聊时传上一轮返回的 sessionId，不传表示新会话
     * - clientPrompt：客户端定制的系统提示词
     * 返回：clientId、实际使用的 agent、sessionId（下次续聊传入）、output（Agent最终回答）
     */
    @GetMapping("/chat")
    public Map<String, Object> chat(@RequestParam String prompt,
                                    @RequestParam(required = false) String agent,
                                    @RequestParam(required = false) String clientId,
                                    @RequestParam(required = false) String sessionId,
                                    @RequestParam(required = false) String clientPrompt) {
        String cid = clientId == null || clientId.isBlank() ? defaultClientId : clientId;
        AgentAdaptor adaptor = agentAdaptorRegistry.get(agent);
        AgentResponse result = adaptor.chat(new AgentRequest(cid, prompt, clientPrompt, sessionId));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("clientId", cid);
        response.put("agent", adaptor.name());
        response.put("sessionId", result.getBrainSession().getSessionId());
        response.put("output", result.getOutput());
        return response;
    }
}
