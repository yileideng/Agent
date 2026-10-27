package com.agent.demo.acpagentserver.acp_server_demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Demo：启动后向 Gateway 的 {@code POST /admin/agents} 注册本 ACP Server。
 *
 * <p>这是控制面动作，不是 ACP 协议的一部分。手动注册也可以：
 * <pre>{@code
 * curl -X POST http://localhost:8080/admin/agents \
 *   -H "Content-Type: application/json" \
 *   -d '{"agentId":"local-ws-qwen","wsUrl":"ws://localhost:8095/acp"}'
 * }</pre>
 *
 * <p>最多尝试 3 次，不做周期重放/注销，保持 demo 简单。
 */
@Component
public class AutoRegister implements ApplicationRunner {

    private final String adminUrl;
    private final String agentId;
    private final String wsUrl;
    private final boolean enabled;

    public AutoRegister(
            @Value("${gateway.admin-url:http://localhost:8080}") String adminUrl,
            @Value("${gateway.auto-register:true}") boolean enabled,
            @Value("${agent.id:local-ws-qwen}") String agentId,
            @Value("${agent.public-ws-url:ws://localhost:8095/acp}") String wsUrl) {
        this.adminUrl = adminUrl.endsWith("/") ? adminUrl.substring(0, adminUrl.length() - 1) : adminUrl;
        this.enabled = enabled;
        this.agentId = agentId;
        this.wsUrl = wsUrl;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        String body = "{\"agentId\":\"" + escape(agentId)
                + "\",\"wsUrl\":\"" + escape(wsUrl) + "\"}";

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(adminUrl + "/admin/agents"))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = client.send(request,
                        HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 201 || status == 200) {
                    System.out.println("[AutoRegister] 注册成功: " + agentId + " -> " + wsUrl);
                    return;
                }
                if (status == 409) {
                    System.out.println("[AutoRegister] 已存在: " + agentId);
                    return;
                }
                System.out.println("[AutoRegister] 第 " + attempt + " 次失败 HTTP "
                        + status + ": " + response.body());
            } catch (Exception e) {
                System.out.println("[AutoRegister] 第 " + attempt + " 次失败: " + e.getMessage());
            }
            sleep();
        }
        System.out.println("[AutoRegister] 注册失败，跳过。可稍后手动 POST /admin/agents");
    }

    private void sleep() {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
