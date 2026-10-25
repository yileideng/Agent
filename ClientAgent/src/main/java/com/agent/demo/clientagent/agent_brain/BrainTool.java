package com.agent.demo.clientagent.agent_brain;

import java.util.Map;

public interface BrainTool {
    String getName();

    String getDescription();

    Map<String, String> getParameters();

    Map<String, String> getRuntimeParameters();

    Map<String, String> execute(Map<String, String> parameters);
}
