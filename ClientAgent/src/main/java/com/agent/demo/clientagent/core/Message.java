package com.agent.demo.clientagent.core;

/**
 * 会话中的一条消息
 */
public class Message {
    // 消息角色：user / assistant / tool
    private String role;

    private String content;

    public Message(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    @Override
    public String toString() {
        return role + ": " + content;
    }
}
