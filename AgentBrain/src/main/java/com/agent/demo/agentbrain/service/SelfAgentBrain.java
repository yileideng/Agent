package com.agent.demo.agentbrain.service;

import com.agent.demo.agentbrain.api.BrainChatRequest;
import com.agent.demo.agentbrain.api.BrainChatResponse;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 自研Agent的决策逻辑。
 * 当前是确定性的演示实现：首轮请求调用 get_name，拿到工具结果后产出最终回答。
 * 后续可替换为真实模型调用，只要保持 BrainChatRequest / BrainChatResponse 契约不变。
 */
@Service
public class SelfAgentBrain {

    public BrainChatResponse chat(BrainChatRequest request) {
        if (request.newSession()) {
            // 首轮：先查询客户端提供的用户信息工具
            return BrainChatResponse.toolCall("get_name", Map.of("name_query", "current_user"));
        }
        // 工具结果回传后：产出最终回答，结束本轮 Agent Loop
        return BrainChatResponse.finalOutput(
                "已获取到用户信息 " + request.userPrompt() + "，回答用户的问题");
    }
}
