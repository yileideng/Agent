package com.agent.demo.clientagent.acp_agent.core;

/**
 * ACP JSON-RPC 调用失败；保留协议错误码，便于适配层决定是否重试。
 */
public class AcpException extends RuntimeException {

    private final int code;

    public AcpException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
