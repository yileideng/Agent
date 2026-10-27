package com.agent.demo.acpagentserver.qwen;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 一个 Qwen Code ACP 子进程；按行读写 JSON-RPC，完全透明代理。
 */
public final class QwenAcpProcess implements AutoCloseable {

    private final Process process;
    private final BufferedWriter writer;

    public QwenAcpProcess(Process process,
                          Consumer<String> onMessage,
                          Consumer<String> onError) {
        this.process = process;
        this.writer = new BufferedWriter(new OutputStreamWriter(
                process.getOutputStream(), StandardCharsets.UTF_8));

        Thread stdout = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        onMessage.accept(line);
                    }
                }
            } catch (IOException e) {
                onError.accept("读取 Qwen stdout 失败: " + e.getMessage());
            } finally {
                onError.accept("Qwen ACP 进程已退出");
            }
        }, "qwen-acp-stdout");
        stdout.setDaemon(true);
        stdout.start();

        Thread stderr = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[Qwen][stderr] " + line);
                }
            } catch (IOException ignored) {
                // 进程退出时忽略
            }
        }, "qwen-acp-stderr");
        stderr.setDaemon(true);
        stderr.start();
    }

    public synchronized void send(String message) {
        try {
            writer.write(message);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            throw new IllegalStateException("写入 Qwen stdin 失败: " + e.getMessage(), e);
        }
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        try {
            writer.close();
        } catch (IOException ignored) {
            // 忽略
        }
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
