# Agent 流式输出设计（ACP SSE）

> 状态：Phase 1 已实现（文本流式 + thinking）
> 范围：文本流式输出 + thinking 独立通道
> 不在本期范围：客户端工具 SSE 传输、聊天断点续传
> 说明：自研 Brain 暂不支持流式，本期走一次性 final
> 关联文档：[2026-10-27-00-agent-gateway-design.md](./2026-10-27-00-agent-gateway-design.md)、[2026-10-27-00-客户端接入指南.md](../user/2026-10-27-00-客户端接入指南.md)、[2026-10-26-23-agent-middleware-design.md](./2026-10-26-23-agent-middleware-design.md)

## 1. 背景与目标

当前 `POST /chat` 是同步接口：Agent 跑完整轮对话后，网关才返回一个 `final` 信封。带来的问题：

- 用户要等十几秒才能看到第一段文字，内容创作场景体验差；
- CLI / Coze 本身已经是流式输出，但 Adaptor 把流收成了完整字符串，流式能力被浪费；
- 客户端无法在生成过程中取消、展示进度或做增量渲染。

本设计要解决的问题：

1. **Agent 侧保持异构**：CLI 的 stream-json、Coze 的 SSE、自研 Brain 的 HTTP，各自协议不同；
2. **客户端侧统一**：客户端只看到 ACP 的 `delta` / `thinking` / `final` / `error`；
3. **逐事件转发**：Adaptor 不能把整段回答收完再发；
4. **thinking 独立通道**：思考过程不混入 assistant 回答，客户端自行决定展示或隐藏；
5. **不破坏现有非流式调用**：`Accept: application/json` 仍返回 `final`；
6. **工具调用保持现状**：客户端工具继续走 HTTP 回调 / WebSocket，不进入聊天流。

## 2. 非目标

- 不做客户端工具的 SSE 传输；
- 不做 thinking 的持久化、审计和跨会话回溯；
- 不做聊天断点续传、`Last-Event-ID` 恢复；
- 不做多 Agent 路由、配额、鉴权（后续独立设计）；
- 不要求所有 Agent 都支持 token 级流式，块级增量即可。

## 3. 总体架构

```text
┌──────────────┐   stream-json / SSE / HTTP   ┌──────────────────┐
│ Agent        │ ───────────────────────────> │ AgentAdaptor     │
│ CLI / Coze   │                              │ 逐事件解析 + 归一化 │
└──────────────┘                              └────────┬─────────┘
                                                       │ AgentStreamSink
                                                       │ delta / finish / error
                                                       ▼
                                              ┌──────────────────┐
                                              │ ChatController   │
                                              │ ACP SSE 编码      │
                                              └────────┬─────────┘
                                                       │ text/event-stream
                                                       ▼
                                              ┌──────────────────┐
                                              │ Client           │
                                              │ 增量渲染 + final  │
                                              └──────────────────┘

客户端工具调用（不经过聊天流）：

Client  <── HTTP 回调 / WebSocket ──  Gateway  <── MCP tools/call ──  CLI Agent
```

两条链路互相独立：

- **聊天流**：只传 assistant 文本、结束、错误；
- **工具链**：MCP Agent 自己调 `/mcp/{clientId}`，网关再通过 HTTP 回调或 WebSocket 找客户端执行。

因此，一个使用 SSE 聊天的客户端，仍然需要 HTTP 回调或 WebSocket 来执行工具；SSE 聊天本身不提供工具通道。

## 4. 客户端协议

### 4.1 端点

```http
POST /chat
Accept: text/event-stream
Content-Type: application/json
```

请求体不变，仍是 ACP `chat` 信封：

```json
{
  "v": "0.1",
  "id": "req-1",
  "type": "chat",
  "payload": {
    "clientId": "xiaohongshu",
    "endUserId": "u-123",
    "sessionId": null,
    "prompt": "帮我写一篇咖啡测评",
    "agent": "cli"
  }
}
```

响应头：

```http
Content-Type: text/event-stream;charset=UTF-8
Cache-Control: no-cache
Connection: keep-alive
X-Accel-Buffering: no
```

`X-Accel-Buffering: no` 用于关闭 Nginx 等反向代理的响应缓冲，避免 delta 被攒着一起发。

### 4.2 事件类型

| 事件 | 方向 | 含义 |
|---|---|---|
| `delta` | 网关 → 客户端 | assistant 文本增量 |
| `thinking` | 网关 → 客户端 | 思考过程增量，独立通道 |
| `final` | 网关 → 客户端 | 本轮结束，带完整 output |
| `error` | 网关 → 客户端 | 本轮失败 |
| `: ping` | 网关 → 客户端 | SSE 注释心跳，不触发事件 |

`tool_call` / `tool_result` 不在聊天流中；它们继续走客户端工具通道。

### 4.3 事件格式

每个 SSE 事件包含：

```text
event: <delta|thinking|final|error>
data: <ACP 信封 JSON>
```

`delta`：

```json
{
  "v": "0.1",
  "id": "req-1",
  "type": "delta",
  "payload": {
    "chatId": "c-1",
    "seq": 1,
    "text": "我先帮你梳理一下"
  }
}
```

- `seq` 从 1 开始单调递增，用于客户端检测乱序/缺口；
- `text` 是增量，不保证是单 token；
- 空字符串不发送。

`thinking`：

```json
{
  "v": "0.1",
  "id": "req-1",
  "type": "thinking",
  "payload": {
    "chatId": "c-1",
    "seq": 2,
    "text": "先确认用户要的是咖啡测评，不是咖啡店推荐"
  }
}
```

- `thinking` 与 `delta` **共用同一个 `seq` 序列**，用于还原两类事件的先后顺序；
- `thinking` 不得混入 `final.output`，客户端渲染时必须与回答分开；
- 默认 UI 可以隐藏；是否展示、是否脱敏由客户端策略决定；
- 没有 thinking 能力的 Agent 不发送该事件，客户端不能假设它一定出现。

`final`：

```json
{
  "v": "0.1",
  "id": "req-1",
  "type": "final",
  "payload": {
    "sessionId": "xiaohongshu:u-123:uuid",
    "chatId": "c-1",
    "agent": "cli",
    "output": "完整回答"
  }
}
```

- `output` 是完整回答，客户端应以它为准；
- 客户端可以保留已收到的 delta 做增量渲染，收到 final 后替换为完整 output。

`error`：

```json
{
  "v": "0.1",
  "id": "req-1",
  "type": "error",
  "payload": {
    "code": "AGENT_ERROR",
    "message": "CLI 执行失败: ...",
    "retryable": false
  }
}
```

### 4.4 事件顺序

正常：

```text
thinking(seq=1) → delta(seq=2) → thinking(seq=3) → delta(seq=4) → ... → final
```

失败：

```text
thinking(seq=1) → delta(seq=2) → error
```

约束：

- `final` 和 `error` 二选一，且只出现一次；
- `final` / `error` 之后连接关闭；
- `delta` / `thinking` 可以在 `final` 之前任意交错，共用 `seq`；
- `final` 和 `error` 不带 `seq`，它们表示流结束；
- 工具调用期间可能长时间没有 `delta`，用 `: ping` 心跳保活。

### 4.5 非流式回退

同一个 `/chat` 保留 JSON 响应：

```http
POST /chat
Accept: application/json
```

返回原来的 `final` 信封。适用于：

- curl / 脚本 / 后台任务；
- 暂不支持 SSE 的旧客户端；
- 调试。

判定规则：`Accept` 包含 `text/event-stream` 才走 SSE，否则走 JSON。

### 4.6 错误语义

| 阶段 | HTTP 状态 | 响应 |
|---|---|---|
| 参数校验失败 | 400 | JSON `error` 信封 |
| Agent 不存在 | 400 | JSON `error` 信封 |
| 流已建立，Agent 失败 | 200 | SSE `error` 事件 |
| 流已建立，客户端断开 | — | 服务端清理，不再发送 |

一旦返回了 `SseEmitter`，HTTP 状态已经是 200；后续失败只能通过 SSE `error` 事件表达。

## 5. Agent 侧归一化

### 5.1 AgentStreamSink

```java
public interface AgentStreamSink {
    void delta(String text);
    default void thinking(String text) {}
    void finish(String sessionId, String output);
    void error(String code, String message);
}
```

- `delta`：一段 assistant 文本；
- `thinking`：一段思考过程，默认空实现；不支持的 Agent 不调用；
- `finish`：本轮结束，`sessionId` 用于下一轮续聊；
- `error`：本轮失败。

Adaptor 只调用这些方法，不直接接触 `SseEmitter`。

### 5.2 AgentAdaptor.chatStream

```java
AgentResponse chat(AgentRequest request);

default void chatStream(AgentRequest request, AgentStreamSink sink) {
    try {
        AgentResponse response = chat(request);
        sink.finish(response.sessionId(), response.output());
    } catch (Exception e) {
        sink.error("AGENT_ERROR", e.getMessage());
    }
}
```

- 默认实现退化为“一次性 final”，保证不实现流式的 Adaptor 仍可用；
- 支持流式的 Adaptor 覆盖 `chatStream`；
- `chat()` 保留，供非流式调用和内部使用。

### 5.3 各 Agent 映射

#### CLI（当前 Qwen Code；Claude Code 需适配）

输入是 `stream-json` 的逐行 JSON：

| CLI 事件 | Adaptor 动作 |
|---|---|
| `system` | 记录 `session_id`，绑定到会话状态 |
| `assistant.message.content[].text` | `sink.delta(text)` |
| `assistant.message.content[].thinking` / `thinking_delta` / `reasoning_content` | `sink.thinking(text)` |
| `result` | `sink.finish(sessionId, answer)` |
| `result.is_error=true` | `sink.error("AGENT_ERROR", ...)` |
| `tool_use` | 忽略；工具由 CLI 自己通过 MCP 调 `/mcp` |

注意：不同 CLI 的 `assistant` 事件可能是“完整消息快照”，也可能是“增量片段”。Adaptor 必须按厂商语义去重/切片，不能盲目把每个 `assistant` 当 delta 追加。

#### Coze

| Coze 事件 | Adaptor 动作 |
|---|---|
| `CONVERSATION_MESSAGE_DELTA` | `sink.delta(content)` |
| `CONVERSATION_CHAT_COMPLETED` | 绑定 `conversationId`，`sink.finish(...)` |
| `CONVERSATION_CHAT_FAILED` | `sink.error("AGENT_ERROR", ...)` |
| `REQUIRES_ACTION` | 本期不处理；客户端工具暂不走 Coze |

Coze 当前没有独立的 thinking 通道，不发 `thinking` 事件。

#### 自研 Brain（暂不支持流式）

本期明确：**自研 Brain 不支持流式。**

- `SelfAgentAdaptor` 不覆盖 `chatStream`，使用默认实现：调用 `chat()` 后只发一个 `final`；
- **不做切块模拟**，避免把假流式当成能力；
- 因此 `self` 路径没有 `delta` / `thinking`，客户端收到一个 `final` 即可；
- 未来 Brain 如果提供 `POST /v1/brain/chat/stream`，再覆盖 `chatStream`；本期不排期。

### 5.4 工具调用边界

- **MCP Agent**：CLI 自己调 `/mcp/{clientId}`；`McpServerController` 收到 `tools/call` 后，通过 `ClientRegistry` 把 `tool_call` 发给客户端（HTTP 回调 / WebSocket），等待 `tool_result`，再包成 MCP `content` 返回 CLI；
- **聊天流不出现 `tool_call`**：客户端的工具通道和聊天通道是分开的；
- 工具执行期间，CLI 不产生 assistant 文本，聊天 SSE 会空闲；用 `: ping` 保活；
- 如果客户端只有 SSE 聊天、没有 HTTP 回调/WS，则它无法执行工具；这是本期明确的限制。

## 6. 服务端实现

### 6.1 ChatController

```text
POST /chat
  ├─ 校验 ChatEnvelope
  ├─ 选择 AgentAdaptor
  ├─ 生成 chatId / responseId
  ├─ Accept 包含 text/event-stream ?
  │    ├─ 是：创建 SseEmitter，调用 adaptor.chatStream(...)
  │    └─ 否：调用 adaptor.chat(...)，返回 final JSON
  └─ 异常：未建流 → 400 JSON；已建流 → SSE error
```

### 6.2 SseEmitter 生命周期

- 超时：默认 5 分钟；
- 心跳：每 15 秒发送 `: ping\n\n`；
- `delta` / `thinking`：立即 `emitter.send(...)`；
- `final`：发送后 `emitter.complete()`；
- `error`：发送后 `emitter.complete()`；
- 客户端断开：`onError` / `onCompletion` 中清理 emitter、取消 Agent（取消能力 P2）；
- 发送失败：`completeWithError`，避免继续写。

### 6.3 线程模型

`chatStream` 可能阻塞（CLI 进程、HTTP 调用）。不要让阻塞逻辑长期占用 Tomcat 请求线程：

- 用专用 `TaskExecutor` 执行 `adaptor.chatStream(...)`；
- `SseEmitter` 在请求线程创建并返回，后续写事件在任务线程执行；
- 执行器要有界，避免大量长连接打满线程池。

本期可以先同步执行，但生产实现应改为异步。

### 6.4 并发与背压

- 同一 `chatId` 的 `delta` / `thinking` 必须按序发送，并共用 `seq`；Adaptor 是单线程读上游，天然有序；
- `SseEmitter.send` 可能因客户端慢而阻塞；本期不做队列，慢客户端直接断开；
- P2 可加有界队列 + 丢弃/断开策略。

## 7. 客户端 SDK

### 7.1 Java SDK

新增：

```java
public interface ChatStreamListener {
    void onDelta(String text);
    default void onThinking(String text) {}
}

public ChatStreamResult chatStream(String prompt,
                                   String agent,
                                   String sessionId,
                                   ChatStreamListener listener)
```

- 请求头 `Accept: text/event-stream`；
- 逐行解析 SSE；
- 收到 `delta` 调 `listener.onDelta`；
- 收到 `thinking` 调 `listener.onThinking`（默认空实现，客户端可选择不展示）；
- 收到 `final` 返回 `ChatStreamResult(sessionId, chatId, agent, output)`；
- 收到 `error` 抛 `AgentException(code, message)`；
- 网络中断/超时抛 `IOException` / `IllegalStateException`。

可以额外提供一个只关心 delta 的便捷重载：

```java
ChatStreamResult chatStream(String prompt, String agent, String sessionId,
                            Consumer<String> onDelta)
```

`chat()` 保留，用于非流式调用。

### 7.2 工具通道不变

- `chatStream` 只负责聊天流；
- 工具调用仍由 `Transport.HTTP_CALLBACK` 或 `Transport.WEBSOCKET` 处理；
- 一个客户端可以同时使用“SSE 聊天 + HTTP 回调工具”或“SSE 聊天 + WS 工具”。

### 7.3 JS / 浏览器

- 用 `fetch` + `ReadableStream` 读 SSE；
- 不用 `EventSource`：它不支持 POST，也不方便带 `Authorization`；
- 解析逻辑与 Java SDK 一致：按空行分事件，读 `event:` / `data:`；
- `thinking` 和 `delta` 分开处理，默认不渲染 thinking。

## 8. 边界与约束

| 问题 | 本期处理 |
|---|---|
| delta 粒度 | 块级即可，不保证单 token |
| final 与 delta 不一致 | final 为准，客户端替换 |
| thinking | 独立 `thinking` 事件；默认 UI 隐藏，客户端可配置展示/关闭 |
| thinking 与回答混流 | 不会，`thinking` 不进入 `final.output` |
| thinking 持久化/审计 | 本期不做 |
| tool_status | 不下发，客户端从工具通道推断 |
| 聊天断线 | 不恢复，客户端重新发起 |
| 客户端取消 | P2；本期至少保证断开后清理 |
| 鉴权 | 本期沿用现状，后续加 token |
| 代理缓冲 | 响应头关闭 + 心跳 |
| 慢客户端 | 本期直接断开，P2 做背压 |
| 自研 Brain | 暂不支持流式，只返回一个 `final` |

## 9. 测试方案

### 9.1 单元测试

- `AgentStreamSink` 到 ACP 事件的映射；
- SSE 事件编码（`delta` / `thinking` / `final` / `error`）；
- `delta` / `thinking` 共用 `seq`，单调递增；
- `thinking` 不进入 `final.output`；
- 空 delta / 空 thinking 不发送。

### 9.2 集成测试

- 用 mock Agent 每 200ms 发一个 delta，`curl -N` 观察到达时间，确认不是一次性返回；
- CLI / Coze 手动验证；
- CLI 同时发 `thinking` 和 `delta`，验证两类事件顺序和通道隔离；
- 工具调用期间 SSE 保持连接，收到 `: ping`，工具结束后继续 delta；
- Agent 中途失败，收到 `error`，连接关闭；
- 参数错误，HTTP 400 + JSON error；
- 客户端中途断开，服务端清理；
- `self` 路径只收到一个 `final`，不出现 `delta` / `thinking`。

### 9.3 回归

- `Accept: application/json` 仍返回 final；
- 原有 `/mcp` 工具链路不受影响；
- HTTP 回调 / WS 工具调用不受影响。

## 10. 实施计划

### Phase 1：文本流式 + thinking（已完成）

1. `AgentStreamSink` + `AgentAdaptor.chatStream`；
2. `ChatController` 支持 `Accept: text/event-stream`；
3. CLI 逐事件转发 `delta` / `thinking`；
4. Coze 逐事件转发 `delta`；
5. 自研 Brain 不覆盖 `chatStream`，走默认一次性 `final`；
6. Java SDK `chatStream` + `ChatStreamListener`；
7. 集成测试 + `curl -N` 验证。

### Phase 2：体验与健壮性（待做）

1. 取消：客户端断开 → 停止 Agent / kill CLI；
2. 异步执行器 + 背压；
3. `tool_status`（按需）；
4. 鉴权与租户上下文。

> 自研 Brain 流式接口暂不排期；`self` 路径本期只有 `final`。

## 11. 待确认

1. `thinking` 默认是否展示？本设计是“下发但客户端默认隐藏”，是否需要按客户端能力协商开关？
2. `thinking` 是否需要脱敏/过滤？本期不做，后续安全设计再定。
3. 取消的语义：客户端断开就 kill CLI，还是让 Agent 跑完？
4. `delta` 与 `thinking` 共用 `seq` 是否满足客户端排序需求？本设计默认共用。
5. 是否需要对 `final` 做签名/校验，防止中间层篡改？后续安全设计再定。

## 12. 当前实现与差距

Phase 1 已落地，剩余是 Phase 2 的健壮性工作：

| 项 | 状态 |
|---|---|
| `AgentStreamSink`（含 thinking） | 已完成 |
| `AgentAdaptor.chatStream` 默认实现 | 已完成 |
| `ChatController` SSE（delta/thinking/final/error） | 已完成 |
| CLI 逐事件转发（delta/thinking） | 已完成 |
| Coze 逐事件转发（delta） | 已完成 |
| 自研 Brain 流式 | 明确不做；走默认一次性 `final`，不切块模拟 |
| Java SDK `chatStream` / `ChatStreamListener` | 已完成 |
| 异步执行器、取消、背压 | 未完成（Phase 2） |
| 集成测试 | 已覆盖 SSE/JSON/CLI/SDK；thinking 长流待补 |

## 附录 A：完整时序

```text
Client                        Gateway                         Agent
  │                              │                              │
  │ POST /chat                   │                              │
  │ Accept: text/event-stream    │                              │
  │─────────────────────────────>│                              │
  │                              │ 选择 Adaptor                 │
  │                              │ 创建 chatId                  │
  │                              │──── chatStream ─────────────>│
  │                              │                              │
  │<──── event: thinking ────────│<─── thinking ────────────────│
  │<──── event: delta ───────────│<─── delta ───────────────────│
  │<──── event: delta ───────────│<─── delta ───────────────────│
  │                              │                              │
  │                              │   （工具调用走 /mcp，独立链路）│
  │                              │                              │
  │<──── event: delta ───────────│<─── delta ───────────────────│
  │<──── event: final ───────────│<─── finish ──────────────────│
  │                              │                              │
  │ 连接关闭                      │                              │
```

## 附录 B：curl 验证

```bash
curl -N -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{
    "v":"0.1",
    "id":"req-1",
    "type":"chat",
    "payload":{
      "clientId":"agent-client",
      "endUserId":"demo-user",
      "prompt":"帮我写一篇咖啡测评",
      "agent":"cli"
    }
  }'
```

预期：`delta` 逐条出现，最后 `final`，连接关闭。
