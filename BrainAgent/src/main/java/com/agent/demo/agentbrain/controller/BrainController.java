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

    /** 自研 Agent 的决策实现。 */
    private final SelfAgentBrain selfAgentBrain;

    public BrainController(SelfAgentBrain selfAgentBrain) {
        this.selfAgentBrain = selfAgentBrain;
    }

    /**
     * POST /v1/brain/chat
     *
     * <p>作用：中间件发起一次 Agent 决策。Brain 无状态，只根据本次输入返回
     * “调用客户端工具”或“最终输出”，会话历史由中间件持有。
     *
     * <p>入参：
     * <ul>
     *   <li>{@code clientId}：调用方客户端（租户）；</li>
     *   <li>{@code endUserId}：终端用户，用于多用户隔离；</li>
     *   <li>{@code sessionId}：中间件持有的会话 ID；</li>
     *   <li>{@code newSession}：是否本轮为新会话；</li>
     *   <li>{@code userPrompt}：用户输入，或上一步工具的执行结果；</li>
     *   <li>{@code clientPrompt}：客户端定制的系统提示词。</li>
     * </ul>
     *
     * <p>返回 200：
     * <ul>
     *   <li>{@code type=CLIENT_TOOL_CALL}：需要中间件调用客户端工具，
     *       带 {@code toolName} + {@code toolParameters}；</li>
     *   <li>{@code type=FINAL_OUTPUT}：本轮结束，带 {@code output}。</li>
     * </ul>
     */
    @PostMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public BrainChatResponse chat(@RequestBody BrainChatRequest request) {
        return selfAgentBrain.chat(request);
    }
}
