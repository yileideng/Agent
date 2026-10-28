package com.agent.demo.clientagent.core.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 跨 Agent 的共享会话历史仓库。
 *
 * <p>一个公共会话下保存所有 Agent 的用户输入、正式回复和真实工具执行记录，
 * 是"切换 Agent 后仍能接着聊"的事实来源。原生 Agent 会话只是加速续接的引用，
 * 丢失后可以按本仓库的历史重建。
 *
 * <p>并发约定：
 * <ul>
 *   <li>每次读写都只持有当前会话的短锁 {@link Conversation#lock}，不做长事务；</li>
 *   <li>整轮对话的串行化使用独立的 {@link #turnLock}，避免模型执行期间阻塞
 *       工具回调线程写入结果（否则会与等待工具结果的模型互相等待）；</li>
 *   <li>每次追加事件立即原子写盘，进程退出不会丢掉已完成记录。</li>
 * </ul>
 */
@Component
public class ConversationStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 单个会话的内存镜像。 */
    private static final class Conversation {
        /** 当前会话文档。 */
        private final Document document;
        /** 读写该文档的短锁。 */
        private final ReentrantLock lock = new ReentrantLock();
        /** 整轮执行锁：同一会话跨 Agent 串行。 */
        private final ReentrantLock turn = new ReentrantLock(true);

        private Conversation(Document document) {
            this.document = document;
        }
    }

    /** 磁盘上的会话文档。字段保持简单类型，便于人工排查与版本演进。 */
    public static final class Document {
        public int version = 1;
        public String clientId;
        public String endUserId;
        public String sessionId;
        public long nextSeq = 1;
        /** Agent 名称 -> 该 Agent 已经同步到的最大事件序号。 */
        public Map<String, AgentCursor> agents = new LinkedHashMap<>();
        public List<Event> events = new ArrayList<>();
    }

    /** 某个 Agent 的差量同步游标。 */
    public static final class AgentCursor {
        public Long syncedThrough;
    }

    /**
     * 一条会话事件。
     *
     * <p>只保存可以安全跨 Agent 转发的内容：用户输入、正式回复、工具调用与结果、
     * 以及失败信息。隐藏思考不入库，避免把一个模型的私有推理喂给另一个模型。
     */
    public static final class Event {
        public long seq;
        public String at;
        public String chatId;
        public String agent;
        /** user / assistant / error / tool_call / tool_result */
        public String type;
        public String text;
        public String tool;
        public String callId;
        /** running / ok / error */
        public String status;
        public Object arguments;
        public String result;
    }

    private final Path directory;
    private final ConcurrentMap<String, Conversation> conversations = new ConcurrentHashMap<>();

    public ConversationStore(@Value("${agent.conversation.directory:data/conversations}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    /** 数据目录，测试与排查使用。 */
    public Path directory() {
        return directory;
    }

    // ── 锁 ──

    /**
     * 该会话的整轮执行锁。调用方必须成对 {@code lock()}/{@code unlock()}。
     */
    public ReentrantLock turnLock(ConversationKey key) {
        return conversation(key).turn;
    }

    // ── 追加事件 ──

    /** 记录本轮用户输入，返回事件序号。 */
    public long appendUser(ConversationKey key, String chatId, String agent, String prompt) {
        Event event = base(key, chatId, agent, "user");
        event.text = prompt;
        return append(key, event);
    }

    /** 记录本轮完整正式回复。 */
    public long appendAssistant(ConversationKey key, String chatId, String agent, String output) {
        Event event = base(key, chatId, agent, "assistant");
        event.text = output;
        event.status = "completed";
        return append(key, event);
    }

    /**
     * 记录失败或中断。
     *
     * @param partialOutput 已经输出给用户的部分正文，可为空
     */
    public long appendFailure(ConversationKey key, String chatId, String agent,
                              String code, String message, String partialOutput) {
        Event event = base(key, chatId, agent, "error");
        event.text = partialOutput == null || partialOutput.isBlank() ? null : partialOutput;
        event.status = "failed";
        event.result = (code == null ? "" : code + ": ") + (message == null ? "" : message);
        return append(key, event);
    }

    /** 记录一次工具调用开始；进程若中途退出，这条记录保持 running，接手方据此判断结果未知。 */
    public long appendToolCall(ConversationKey key, String chatId, String agent,
                               String tool, String callId, Object arguments) {
        Event event = base(key, chatId, agent, "tool_call");
        event.tool = tool;
        event.callId = callId;
        event.status = "running";
        event.arguments = arguments;
        return append(key, event);
    }

    /** 记录一次工具调用的真实结果。 */
    public long appendToolResult(ConversationKey key, String chatId, String agent,
                                 String tool, String callId, boolean ok, String result) {
        Event event = base(key, chatId, agent, "tool_result");
        event.tool = tool;
        event.callId = callId;
        event.status = ok ? "ok" : "error";
        event.result = result;
        return append(key, event);
    }

    // ── 读取 ──

    /**
     * 读取指定区间的事件副本：{@code seq > afterSeq} 且 {@code seq < beforeSeq}。
     *
     * <p>用于在追加本轮用户输入之后，仍然只取"本轮之前"的历史。
     */
    public List<Event> eventsBetween(ConversationKey key, long afterSeq, long beforeSeq) {
        Conversation conversation = conversation(key);
        conversation.lock.lock();
        try {
            List<Event> copy = new ArrayList<>();
            for (Event event : conversation.document.events) {
                if (event.seq > afterSeq && event.seq < beforeSeq) {
                    copy.add(copy(event));
                }
            }
            return copy;
        } finally {
            conversation.lock.unlock();
        }
    }

    /** 该 Agent 已经同步到的事件序号；没有记录时返回 0。 */
    public long syncedThrough(ConversationKey key, String agent) {
        Conversation conversation = conversation(key);
        conversation.lock.lock();
        try {
            AgentCursor cursor = conversation.document.agents.get(agent);
            return cursor == null || cursor.syncedThrough == null ? 0L : cursor.syncedThrough;
        } finally {
            conversation.lock.unlock();
        }
    }

    /** 推进该 Agent 的同步游标。只能在整轮成功落库后调用。 */
    public void markSynced(ConversationKey key, String agent, long seq) {
        Conversation conversation = conversation(key);
        conversation.lock.lock();
        try {
            conversation.document.agents
                    .computeIfAbsent(agent, ignored -> new AgentCursor())
                    .syncedThrough = seq;
            persist(conversation);
        } finally {
            conversation.lock.unlock();
        }
    }

    /** 当前最大事件序号；没有事件时为 0。 */
    public long lastSeq(ConversationKey key) {
        Conversation conversation = conversation(key);
        conversation.lock.lock();
        try {
            List<Event> events = conversation.document.events;
            return events.isEmpty() ? 0L : events.get(events.size() - 1).seq;
        } finally {
            conversation.lock.unlock();
        }
    }

    // ── 内部实现 ──

    private Event base(ConversationKey key, String chatId, String agent, String type) {
        Event event = new Event();
        event.chatId = chatId;
        event.agent = agent;
        event.type = type;
        event.at = Instant.now().toString();
        return event;
    }

    private long append(ConversationKey key, Event event) {
        Conversation conversation = conversation(key);
        conversation.lock.lock();
        try {
            event.seq = conversation.document.nextSeq++;
            conversation.document.events.add(event);
            persist(conversation);
            return event.seq;
        } finally {
            conversation.lock.unlock();
        }
    }

    private Conversation conversation(ConversationKey key) {
        return conversations.computeIfAbsent(key.id(), ignored -> new Conversation(load(key)));
    }

    private Document load(ConversationKey key) {
        Path file = fileFor(key);
        if (!Files.exists(file)) {
            Document document = new Document();
            document.clientId = key.clientId();
            document.endUserId = key.endUserId();
            document.sessionId = key.sessionId();
            return document;
        }
        try {
            Document document = JSON.readValue(Files.readString(file, StandardCharsets.UTF_8), Document.class);
            if (document == null) {
                throw new IllegalStateException("共享会话文件为空: " + file);
            }
            if (document.events == null) {
                document.events = new ArrayList<>();
            }
            if (document.agents == null) {
                document.agents = new LinkedHashMap<>();
            }
            if (document.nextSeq < 1) {
                document.nextSeq = 1;
            }
            // 兼容手工修复或旧格式：确保 nextSeq 大于已有最大序号。
            for (Event event : document.events) {
                if (event.seq >= document.nextSeq) {
                    document.nextSeq = event.seq + 1;
                }
            }
            return document;
        } catch (IOException e) {
            // 损坏时明确失败，绝不当作空历史覆盖用户记录。
            throw new IllegalStateException("读取共享会话失败: " + file + "；" + e.getMessage(), e);
        }
    }

    private void persist(Conversation conversation) {
        Document document = conversation.document;
        Path file = fileFor(new ConversationKey(
                document.clientId, document.endUserId, document.sessionId));
        try {
            Files.createDirectories(directory);
            Path temp = Files.createTempFile(directory, "conversation-", ".tmp");
            Files.writeString(temp, JSON.writeValueAsString(document), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("写入共享会话失败: " + file, e);
        }
    }

    private Path fileFor(ConversationKey key) {
        return directory.resolve(hash(key) + ".json");
    }

    private String hash(ConversationKey key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(key.id().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private Event copy(Event source) {
        Event event = new Event();
        event.seq = source.seq;
        event.at = source.at;
        event.chatId = source.chatId;
        event.agent = source.agent;
        event.type = source.type;
        event.text = source.text;
        event.tool = source.tool;
        event.callId = source.callId;
        event.status = source.status;
        event.arguments = source.arguments;
        event.result = source.result;
        return event;
    }
}
