package com.agent.demo.acpagentserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 外部 ACP Server 接入 demo 的启动入口。
 *
 * <p>完整接入说明见模块根目录的 {@code README.md}。
 */
@SpringBootApplication
public class AcpAgentServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AcpAgentServerApplication.class, args);
    }
}
