package com.agent.demo.clientagent.agent_brain;

public enum ChatResultType {
    // 对话结果类型是客户端工具调用
    CLIENT_TOOL_CALL,

    // 对话结果类型是最终输出，Agent Loop结束
    FINAL_OUTPUT
}
