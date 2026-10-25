package com.agent.demo;

import com.agent.demo.agent_brain.ChatResult;
import com.agent.demo.client.Client;

public class Main {
    public static void main(String[] args) {
        Client client = new Client();
        client.init();
        ChatResult result = client.chat("我叫什么名字？");
        System.out.println("[Main] 最终输出: " + result.getOutput());
    }
}
