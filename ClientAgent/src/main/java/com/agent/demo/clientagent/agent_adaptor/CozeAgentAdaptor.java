package com.agent.demo.clientagent.agent_adaptor;

import com.agent.demo.clientagent.agent_brain.BrainSession;
import com.agent.demo.clientagent.agent_brain.ChatResult;
import com.agent.demo.clientagent.agent_brain.ChatResultType;
import com.agent.demo.clientagent.agent_brain.Message;
import com.coze.openapi.client.chat.CreateChatReq;
import com.coze.openapi.client.chat.model.ChatEvent;
import com.coze.openapi.client.chat.model.ChatEventType;
import com.coze.openapi.service.auth.TokenAuth;
import com.coze.openapi.service.service.CozeAPI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对接扣子(Coze)托管智能体的适配器。
 * 智能体的prompt、插件、知识库在扣子平台上配置，代码侧只做对话转发。
 */
@Component
public class CozeAgentAdaptor implements AgentAdaptor {

    private final String apiToken;
    private final String botId;
    private final String baseUrl;
    // 未配置token时不初始化，避免应用启动失败
    private volatile CozeAPI coze;

    // 本地sessionId -> 扣子conversationId 的映射，demo用内存Map，实际应落库
    private final Map<String, String> sessionConversationMap = new ConcurrentHashMap<>();

    // todo: 收到 CONVERSATION_CHAT_REQUIRES_ACTION 事件(扣子要求执行本地工具)时，
    //       通过它把工具调用路由回客户端，接入现有客户端工具调用链
    private ClientToolExecutor clientToolExecutor;

    public CozeAgentAdaptor(@Value("${coze.api-token:}") String apiToken,
                            @Value("${coze.bot-id:}") String botId,
                            @Value("${coze.base-url:https://api.coze.cn}") String baseUrl) {
        this.apiToken = apiToken;
        this.botId = botId;
        this.baseUrl = baseUrl;
    }

    @Override
    public String name() {
        return "coze";
    }

    private CozeAPI coze() {
        if (coze == null) {
            synchronized (this) {
                if (coze == null) {
                    if (apiToken.isEmpty() || botId.isEmpty()) {
                        throw new IllegalStateException("未配置coze.api-token或coze.bot-id，无法使用coze适配器");
                    }
                    coze = new CozeAPI.Builder()
                            .baseURL(baseUrl)
                            .auth(new TokenAuth(apiToken))
                            .readTimeout(60000)
                            .build();
                }
            }
        }
        return coze;
    }

    @Override
    public void setClientToolExecutor(ClientToolExecutor clientToolExecutor) {
        this.clientToolExecutor = clientToolExecutor;
    }

    @Override
    public ChatResult chat(String userPrompt, String clientPrompt, String sessionId) {
        BrainSession brainSession = new BrainSession().initSession(sessionId);
        brainSession.addMessage(new Message("user", userPrompt));

        // coze的Message与本项目的Message同名，用全限定名区分
        var userMessage = com.coze.openapi.client.connversations.message.model.Message
                .buildUserQuestionText(userPrompt);
        CreateChatReq.CreateChatReqBuilder<?, ?> reqBuilder = CreateChatReq.builder()
                .botID(botId)
                // 演示用固定用户标识，实际应传客户端用户ID
                .userID("demo-user")
                .messages(Collections.singletonList(userMessage));
        // 已建立的会话带上conversationId，保持多轮上下文
        String conversationId = sessionConversationMap.get(brainSession.getSessionId());
        if (conversationId != null) {
            reqBuilder.conversationID(conversationId);
        }

        // 流式调用并聚合最终输出
        StringBuilder answer = new StringBuilder();
        for (ChatEvent event : coze().chat().stream(reqBuilder.build()).blockingIterable()) {
            if (event.getEvent() == ChatEventType.CONVERSATION_MESSAGE_DELTA) {
                answer.append(event.getMessage().getContent());
            } else if (event.getEvent() == ChatEventType.CONVERSATION_CHAT_COMPLETED) {
                sessionConversationMap.put(brainSession.getSessionId(), event.getChat().getConversationID());
            } else if (event.getEvent() == ChatEventType.CONVERSATION_CHAT_FAILED) {
                throw new IllegalStateException("扣子对话失败: " + event);
            }
        }

        brainSession.addMessage(new Message("assistant", answer.toString()));
        ChatResult result = new ChatResult();
        result.setBrainSession(brainSession);
        result.setResultType(ChatResultType.FINAL_OUTPUT);
        result.setOutput(answer.toString());
        return result;
    }
}
