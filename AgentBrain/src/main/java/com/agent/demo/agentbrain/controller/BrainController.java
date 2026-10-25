package com.agent.demo.agentbrain.controller;

import com.agent.demo.agentbrain.api.BrainChatRequest;
import com.agent.demo.agentbrain.api.BrainChatResponse;
import com.agent.demo.agentbrain.service.SelfAgentBrain;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 自研Agent服务的对外接口。
 * 只由中间件的 AgentAdaptor 调用，客户端不直接访问。
 */
@RestController
@RequestMapping("/v1/brain")
public class BrainController {

    private final SelfAgentBrain selfAgentBrain;

    public BrainController(SelfAgentBrain selfAgentBrain) {
        this.selfAgentBrain = selfAgentBrain;
    }

    /**
     * POST /v1/brain/chat
     * 中间件发起一次 Agent 决策：传本次输入与会话标识，返回工具调用或最终输出。
     * 请求体：{"clientId","sessionId","newSession","userPrompt","clientPrompt"}
     * 响应体：{"type":"CLIENT_TOOL_CALL|FINAL_OUTPUT","toolName","toolParameters","output"}
     */
    @PostMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public BrainChatResponse chat(@RequestBody BrainChatRequest request) {
        return selfAgentBrain.chat(request);
    }
}
