package com.agent.demo.clientagent.core;

/**
 * Agent 流式输出的统一回调。
 *
 * <p>不同 Agent 的流式协议不同（CLI 的 stream-json、扣子的 SSE、自研 Brain 的 HTTP 流），
 * 适配器负责把厂商事件翻译成这些方法；网关再把它们转成统一的 ACP
 * {@code delta} / {@code thinking} / {@code final} / {@code error} 推送给客户端。
 */
public interface AgentStreamSink {

    /** 一段 assistant 文本增量。 */
    void delta(String text);

    /**
     * 一段思考过程增量；不支持 thinking 的 Agent 不会调用。
     */
    default void thinking(String text) {
    }

    /**
     * 本轮结束。
     *
     * @param sessionId 下一轮续聊凭证
     * @param output    完整回答（客户端可用它对账已收到的 delta）
     */
    void finish(String sessionId, String output);

    /** 本轮失败。 */
    void error(String code, String message);

    /** 不关心流式事件的调用方使用（例如旧的同步 chat()）。 */
    AgentStreamSink NOOP = new AgentStreamSink() {
        @Override
        public void delta(String text) {
        }

        @Override
        public void finish(String sessionId, String output) {
        }

        @Override
        public void error(String code, String message) {
        }
    };
}
