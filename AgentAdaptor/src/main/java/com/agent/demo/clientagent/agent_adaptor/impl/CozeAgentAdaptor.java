package com.agent.demo.clientagent.agent_adaptor.impl;

import com.agent.demo.clientagent.agent_adaptor.AgentAdaptor;
import com.agent.demo.clientagent.core.AgentRequest;
import com.agent.demo.clientagent.core.AgentSessionStore;
import com.agent.demo.clientagent.core.AgentResponse;
import com.coze.openapi.client.chat.CreateChatReq;
import com.coze.openapi.client.chat.model.ChatEvent;
import com.coze.openapi.client.chat.model.ChatEventType;
import com.coze.openapi.service.auth.TokenAuth;
import com.coze.openapi.service.service.CozeAPI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 对接扣子(Coze)托管智能体的适配器。
 * 智能体的prompt、插件、知识库在扣子平台上配置，代码侧只做对话转发。
 */
@Component
public class CozeAgentAdaptor implements AgentAdaptor {

    /** 扣子 API Token；为空时适配器不可用。 */
    private final String apiToken;
    /** 扣子 Bot ID。 */
    private final String botId;
    /** 扣子 API 地址。 */
    private final String baseUrl;
    /** 延迟初始化：未配置 token 时不创建客户端，避免应用启动失败。 */
    private volatile CozeAPI coze;
    /** 会话归属，保存 conversationId 映射。 */
    private final AgentSessionStore sessionStore;

    public CozeAgentAdaptor(@Value("${coze.api-token:}") String apiToken,
                            @Value("${coze.bot-id:}") String botId,
                            @Value("${coze.base-url:https://api.coze.cn}") String baseUrl,
                            AgentSessionStore sessionStore) {
        this.apiToken = apiToken;
        this.botId = botId;
        this.baseUrl = baseUrl;
        this.sessionStore = sessionStore;
    }

    @Override
    public String name() {
        return "coze";
    }

    @Override
    public String description() {
        return "扣子(Coze)托管智能体，Agent能力在扣子平台配置";
    }

    @Override
    public List<String> capabilities() {
        return List.of("chat");
    }

    @Override
    public boolean available() {
        return !apiToken.isEmpty() && !botId.isEmpty();
    }

    @Override
    public String unavailableReason() {
        return available() ? null : "未配置 coze.api-token 或 coze.bot-id";
    }

    private CozeAPI coze() {
        if (coze == null) {
            synchronized (this) {
                if (coze == null) {
                    if (!available()) {
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
    public AgentResponse chat(AgentRequest request) {
        String localSessionId = sessionStore.resolveSessionId(
                request.clientId(), request.endUserId(), request.sessionId());
        AgentSessionStore.SessionState state =
                sessionStore.getOrCreate(name(), request.clientId(), request.endUserId(), localSessionId);

        // 使用 Coze SDK 的 Message 构造用户消息
        var userMessage = com.coze.openapi.client.connversations.message.model.Message
                .buildUserQuestionText(request.userPrompt());
        CreateChatReq.CreateChatReqBuilder<?, ?> reqBuilder = CreateChatReq.builder()
                .botID(botId)
                // 以客户端ID作为用户标识，后续接入多租户后可细化为 endUserId
                .userID(request.clientId())
                .messages(Collections.singletonList(userMessage));
        // 已建立的会话带上conversationId，保持多轮上下文
        String conversationId = state.externalSessionId();
        if (conversationId != null) {
            reqBuilder.conversationID(conversationId);
        }

        // 流式调用并聚合最终输出
        StringBuilder answer = new StringBuilder();
        for (ChatEvent event : coze().chat().stream(reqBuilder.build()).blockingIterable()) {
            if (event.getEvent() == ChatEventType.CONVERSATION_MESSAGE_DELTA) {
                answer.append(event.getMessage().getContent());
            } else if (event.getEvent() == ChatEventType.CONVERSATION_CHAT_COMPLETED) {
                state.bindExternalSessionId(event.getChat().getConversationID());
            } else if (event.getEvent() == ChatEventType.CONVERSATION_CHAT_FAILED) {
                throw new IllegalStateException("扣子对话失败: " + event);
            }
        }

        return new AgentResponse(localSessionId, answer.toString());
    }
}
