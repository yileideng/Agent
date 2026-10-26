package com.agent.demo.agentsdk;

/**
 * 流式对话回调。
 *
 * <p>只需要处理 assistant 文本时，可直接用 lambda：
 * <pre>{@code
 * client.chatStream("帮我写一篇笔记", null, null, text -> System.out.print(text));
 * }</pre>
 */
@FunctionalInterface
public interface ChatStreamListener {

    /** 一段 assistant 文本增量。 */
    void onDelta(String text);

    /**
     * 一段思考过程增量；默认忽略，需要展示时覆盖。
     */
    default void onThinking(String text) {
    }
}
