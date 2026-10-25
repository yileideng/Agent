package com.agent.demo.clientagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ClientAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClientAgentApplication.class, args);
    }

}
