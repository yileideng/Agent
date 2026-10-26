package com.agent.demo.agentsdk;

/**
 * 流式对话失败；code 来自 CAP error 信封。
 */
public class AgentException extends RuntimeException {

    private final String code;

    public AgentException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
