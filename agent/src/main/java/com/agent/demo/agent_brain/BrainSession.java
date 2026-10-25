package com.agent.demo.agent_brain;

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

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public BrainSession initSession(String sessionId) {
        BrainSession brainSession = new BrainSession();
        if (sessionId == null || sessionId.isEmpty()) {
            // 初始化session
            brainSession.setSessionId(UUID.randomUUID().toString());
            return brainSession;
        }
        // 如果sessionId存在，则可以查询到对应的session信息，这里暂时只设置sessionId
        brainSession.setSessionId(sessionId);
        return brainSession;
    }
}
