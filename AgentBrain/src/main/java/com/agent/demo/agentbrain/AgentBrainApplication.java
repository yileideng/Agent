package com.agent.demo.agentbrain;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 自研 Agent 服务：只负责 Agent 决策，不持有会话状态。
 * 中间件通过 HTTP 调用它，因此它可以独立部署、独立替换。
 */
@SpringBootApplication
public class AgentBrainApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentBrainApplication.class, args);
    }
}
