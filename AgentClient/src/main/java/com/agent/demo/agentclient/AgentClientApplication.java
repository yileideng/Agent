package com.agent.demo.agentclient;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 自研客户端：独立进程运行，向中间件注册工具、接收工具回调，并通过中间件与Agent对话。
 * 与真实外部客户端使用完全相同的注册/回调协议。
 */
@SpringBootApplication
@EnableScheduling
public class AgentClientApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentClientApplication.class, args);
    }
}
