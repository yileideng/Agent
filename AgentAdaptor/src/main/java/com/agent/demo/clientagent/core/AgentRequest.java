package com.agent.demo.clientagent.core;

/**
 * Agent 调用请求：把客户端上下文和对话参数统一收敛到一个对象里。
 * clientId 是租户隔离的扩展点，后续鉴权/配额/工具裁剪都从它派生。
 *
 * @param clientId     发起调用的客户端（租户）
 * @param endUserId    终端用户标识（MOBILE 客户端按用户/设备路由工具调用时使用，SERVER 可空）
 * @param userPrompt   用户输入，或客户端工具的执行结果
 * @param clientPrompt 客户端定制的系统提示词
 * @param sessionId    会话ID，为空表示新建会话
 * @param chatId       本轮对话ID，用于关联本轮的所有工具调用
 */
public record AgentRequest(String clientId,
                           String endUserId,
                           String userPrompt,
                           String clientPrompt,
                           String sessionId,
                           String chatId) {

    public AgentRequest {
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("userPrompt不能为空");
        }
    }
}
