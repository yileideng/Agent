package com.agent.demo.agentbrain.service;

import com.agent.demo.agentbrain.api.BrainChatRequest;
import com.agent.demo.agentbrain.api.BrainChatResponse;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 自研Agent的决策逻辑。
 *
 * <p>当前是确定性的演示实现，用来验证"一轮对话内多次工具调用"：
 * <ol>
 *   <li>新会话先调用 {@code get_name}；</li>
 *   <li>拿到姓名结果后，再调用 {@code update_name}；</li>
 *   <li>第二次工具结果回来后，产出最终回答。</li>
 * </ol>
 *
 * <p>后续可替换为真实模型调用，只要保持 BrainChatRequest / BrainChatResponse 契约不变。
 * 两次工具调用共享同一个 chatId、使用不同的 callId，由中间件负责按 callId 关联结果。
 */
@Service
public class SelfAgentBrain {

    public BrainChatResponse chat(BrainChatRequest request) {
        if (request.newSession()) {
            // 首轮：先查询客户端提供的用户信息工具
            return BrainChatResponse.toolCall("get_name", Map.of());
        }

        String toolResult = request.userPrompt() == null ? "" : request.userPrompt();
        // 第一次工具结果（姓名）回来后，再发起一次工具调用，演示同一 chatId 下的多次调用
        if (toolResult.contains("\"name\"") && !toolResult.contains("李四")) {
            return BrainChatResponse.toolCall("update_name", Map.of("updated_name", "李四"));
        }

        // 第二次工具结果回来后：产出最终回答，结束本轮 Agent Loop
        return BrainChatResponse.finalOutput(
                "已完成用户信息查询与更新，最近一次工具结果: " + toolResult);
    }
}
