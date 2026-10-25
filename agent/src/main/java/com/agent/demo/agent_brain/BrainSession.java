package com.agent.demo.agent_brain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class BrainSession {
    private String sessionId;
    /*
     * 消息列表
     */
    private List<Message> messageList;

    /*
     * 客户端共享变量，在AgentBrain修改后，会同步给客户端进行修改，JSON格式
     */
    private Map<String, String> sharedVariables;

    public String getSessionId() {
        return sessionId;
    }

    public List<Message> getMessageList() {
        return messageList;
    }

    public BrainSession initSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            // 初始化session
            this.sessionId = UUID.randomUUID().toString();
        } else {
            // 如果sessionId存在，则沿用该sessionId
            this.sessionId = sessionId;
        }
        this.messageList = new ArrayList<>();
        this.sharedVariables = new HashMap<>();
        return this;
    }

    public void addMessage(Message message) {
        this.messageList.add(message);
    }
}
