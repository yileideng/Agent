package com.agent.demo.acpagentserver.register;

import jakarta.annotation.PreDestroy;
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
 * 启动后自动向 Gateway 注册本 Agent Server。
 *
 * <p>测试 demo 用：注册失败重试；注册成功仍周期性重放，防止 Gateway 重启后注册丢失。
 * 关闭时尽力注销。
 */
@Component
public class AgentAutoRegistrar implements ApplicationRunner {

    private final String adminUrl;
    private final String agentId;
    private final String publicWsUrl;
    private final boolean enabled;
    private final long retryMs;
    private final long reRegisterMs;
    private final HttpClient httpClient;

    private volatile boolean running = true;
    private Thread worker;

    public AgentAutoRegistrar(
            @Value("${gateway.admin-url:http://localhost:8080}") String adminUrl,
            @Value("${gateway.auto-register:true}") boolean enabled,
            @Value("${gateway.register-retry-ms:5000}") long retryMs,
            @Value("${gateway.re-register-ms:30000}") long reRegisterMs,
            @Value("${agent.id:local-ws-qwen}") String agentId,
            @Value("${agent.public-ws-url:ws://localhost:8095/acp}") String publicWsUrl) {
        this.adminUrl = trimTrailingSlash(adminUrl);
        this.agentId = agentId;
        this.publicWsUrl = publicWsUrl;
        this.enabled = enabled;
        this.retryMs = retryMs;
        this.reRegisterMs = reRegisterMs;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            System.out.println("[AutoRegister] 已关闭");
            return;
        }
        worker = new Thread(this::loop, "agent-auto-register");
        worker.setDaemon(true);
        worker.start();
    }

    private void loop() {
        while (running) {
            boolean registered = register();
            sleep(registered ? reRegisterMs : retryMs);
        }
    }

    private boolean register() {
        try {
            String json = "{\"agentId\":\"" + escape(agentId)
                    + "\",\"wsUrl\":\"" + escape(publicWsUrl) + "\"}";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(adminUrl + "/admin/agents"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 201 || status == 200) {
                System.out.println("[AutoRegister] 注册成功: " + agentId + " -> " + publicWsUrl);
                return true;
            }
            if (status == 409) {
                System.out.println("[AutoRegister] 已存在，视为成功: " + agentId);
                return true;
            }
            System.out.println("[AutoRegister] 注册失败 HTTP " + status + ": " + response.body());
        } catch (Exception e) {
            System.out.println("[AutoRegister] 注册失败: " + e.getMessage());
        }
        return false;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(Math.max(1000, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(adminUrl + "/admin/agents/" + agentId))
                    .timeout(Duration.ofSeconds(2))
                    .DELETE()
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // Gateway 可能已停止
        }
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
