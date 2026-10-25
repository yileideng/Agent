package com.agent.demo.clientagent.client;

import java.util.Map;

public interface ClientTool {
    String getName();

    String getDescription();

    Map<String, String> getParameters();

    Map<String, String> getRuntimeParameters();

    Map<String, String> execute(Map<String, String> parameters);
}
