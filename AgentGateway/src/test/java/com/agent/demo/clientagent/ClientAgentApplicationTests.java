package com.agent.demo.clientagent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import com.agent.demo.clientagent.acp_agent.AcpAgentRegistry;
import com.agent.demo.clientagent.acp_agent.impl.adaptor.QwenCodeAgentAdaptor;
import com.agent.demo.clientagent.acp_agent.impl.adaptor.CodexAgentAdaptor;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "agent.codex.command=codex-not-installed-for-test")
class ClientAgentApplicationTests {

    @Autowired AcpAgentRegistry agents;

    @Test
    void contextLoads() {
        assertInstanceOf(QwenCodeAgentAdaptor.class, agents.get("cli"));
        assertInstanceOf(CodexAgentAdaptor.class, agents.get("codex"));
        assertFalse(agents.get("codex").available());
        assertTrue(agents.get("codex").unavailableReason().contains("AGENT_CODEX_COMMAND"));
    }

}
