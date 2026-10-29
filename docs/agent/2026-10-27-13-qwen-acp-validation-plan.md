# 用 Qwen Code ACP 验证 AgentAdaptor：技术方案

> 目标：不改客户端 ACP，先把 Gateway 作为 **ACP Client** 接到 Qwen Code 的 ACP 模式，验证“一个通用 ACP Client 接多个 ACP Agent”的可行性
> 状态：**已实施（文本/流式通过；MCP 工具受 Qwen 0.26 ACP 限制）**
> 相关：[ACP 对齐方案](./2026-10-27-13-acp-alignment-plan.md)、[Agent 生态讨论](./2026-10-27-13-agent-ecosystem-lessons.md)

## 1. 为什么用 Qwen Code 验证

本机 Qwen Code 0.26.0 已支持 ACP：

```bash
qwen --acp
```

实测 `initialize` 返回：

```json
{
  "protocolVersion": 1,
  "agentInfo": { "name": "qwen-code", "version": "0.26.0" },
  "agentCapabilities": {
    "loadSession": true,
    "promptCapabilities": { "image": true, "audio": true, "embeddedContext": true },
    "sessionCapabilities": { "list": {}, "resume": {} },
    "mcpCapabilities": { "sse": true, "http": true }
  },
  "authMethods": [
    { "id": "openai", "name": "Use OpenAI API key" }
  ]
}
```

这说明：

- Qwen 是 ACP **Agent**，Gateway 是 ACP **Client**；
- 传输是 stdio + JSON-RPC 2.0；
- 支持 `loadSession`、图片/音频/embedded context、MCP HTTP/SSE；
- 认证走 `openai` 方法，实际 key 通过 CLI 参数/环境/配置提供。

## 2. 方案边界

本期只做 **ACP 出口**：

```text
Client（ACP，不变）
   │ POST /chat
   ▼
Gateway
   │  AcpClientAgent（新）
   │  stdio JSON-RPC
   ▼
Qwen Code（qwen --acp）
   │
   └── MCP HTTP ──> Gateway /mcp/{clientId} ──> Client 工具
```

不做：

- 不把客户端 ACP 改成 ACP；
- 不做 ACP 入口（Client → Gateway 的 ACP 方法）；
- 不做远程 ACP Agent 的 WS 传输；
- 不做完整的权限/文件/终端代理（先验证主链路）。

## 3. 新增代码结构

```text
AgentAdaptor/src/main/java/com/agent/demo/clientagent/agent_adaptor/acp/
├── AcpClientAgent.java        // implements AcpAgent，名字 qwen-acp
├── AcpAgentProfile.java        // command/args/env/model/cwd 等配置
├── AcpConnection.java          // stdio JSON-RPC：请求、响应、通知
├── AcpSession.java             // 一个 ACP 会话：进程 + connection + sessionId
├── AcpEventMapper.java         // session/update -> AgentStreamSink
├── AcpPromptBuilder.java       // prompt + clientPrompt -> ContentBlock[]
└── AcpToolConfig.java          // 生成 session/new.mcpServers
```

### 3.1 AcpConnection

职责：

- 启动 `qwen --acp` 子进程；
- 按行读写 JSON-RPC 2.0；
- 维护 `id -> CompletableFuture<Response>`；
- 收到 `session/update` 通知时分发给当前会话；
- 把 stderr 当日志；
- 进程退出时让所有 pending 请求失败。

核心接口：

```java
public final class AcpConnection implements AutoCloseable {
    void start();
    JsonNode request(String method, Object params, Duration timeout);
    void notify(String method, Object params);
    void onNotification(Consumer<JsonNode> handler);
    void close();
}
```

### 3.2 AcpSession

一个本地会话对应一个 Qwen ACP 子进程：

```java
final class AcpSession {
    Process process;
    AcpConnection connection;
    String acpSessionId;
    boolean initialized;
    Set<String> capabilities;
    ReentrantLock promptLock;
}
```

生命周期：

- 首次 `chat` 时创建进程；
- `initialize` 一次；
- `session/new` 一次，保存 `acpSessionId`；
- 同一本地 session 的多轮复用进程和 ACP session；
- 空闲 TTL（如 30 分钟）或应用关闭时 `close()`。

### 3.3 AcpClientAgent

```java
@Component
public class AcpClientAgent implements AcpAgent {
    public String name() { return "qwen-acp"; }
    public List<String> capabilities() {
        return List.of("chat", "streaming", "thinking", "mcp", "acp");
    }
    public AgentResponse chat(AgentRequest request) {
        // 用 chatStream + 收集器，避免维护两套逻辑
    }
    public void chatStream(AgentRequest request, AgentStreamSink sink) {
        // 1. 取/建 AcpSession
        // 2. ensureInitialized + ensureAcpSession
        // 3. session/prompt
        // 4. session/update -> sink
        // 5. prompt 响应 -> sink.finish
    }
}
```

## 4. 关键流程

### 4.1 初始化

```text
Gateway -> Qwen: initialize { protocolVersion:1, clientCapabilities, clientInfo }
Qwen -> Gateway: { protocolVersion, agentCapabilities, authMethods }
```

`clientCapabilities` 先保守：

```json
{
  "fs": { "readTextFile": false, "writeTextFile": false },
  "terminal": false
}
```

这样 Qwen 不会要求 Gateway 代理文件/终端；它使用自己的工具。

### 4.2 认证

Qwen 返回 `authMethods[0].id = "openai"`。

优先在启动参数里配置：

```text
qwen --acp \
  --auth-type openai \
  --openai-api-key <key> \
  --openai-base-url <url> \
  --model <model>
```

如果 `session/new` 返回 `auth_required`，再调用：

```json
{ "method": "authenticate", "params": { "methodId": "openai" } }
```

注意：实测只设置 `OPENAI_API_KEY` 环境变量时 Qwen 仍报缺少 key；需要以实际 CLI 版本为准，优先复用现有 `application.yml` 的 CLI 参数。key 不要写进代码仓库。

### 4.3 创建会话

```json
{
  "method": "session/new",
  "params": {
    "cwd": "C:\\...\\client-agent-workspaces\\...",
    "mcpServers": [
      {
        "name": "client-tools",
        "type": "http",
        "url": "http://localhost:8080/mcp/{clientId}?endUserId=...&sessionId=...&chatId=..."
      }
    ]
  }
}
```

返回：

```json
{ "sessionId": "sess_..." }
```

MCP URL 复用现有 `McpServerController`，Qwen 通过它发现/调用客户端工具。

### 4.4 发送 prompt

ACP 的 prompt 是 ContentBlock 数组：

```json
{
  "method": "session/prompt",
  "params": {
    "sessionId": "sess_...",
    "prompt": [
      { "type": "text", "text": "用户输入" }
    ]
  }
}
```

`clientPrompt` 的处理：

- ACP 没有标准 `systemPrompt` 字段；
- 先方案：把 `clientPrompt` 作为第一个 text block 前置，加明确分隔；
- 更好的方案：Qwen 支持 `--append-system-prompt` 时，在进程启动参数里注入；但该值会变成会话级，不是每轮可变；
- 不能假设 ACP 自动注入系统提示。

### 4.5 接收流式事件

Qwen 会发 `session/update` 通知：

```json
{
  "method": "session/update",
  "params": {
    "sessionId": "sess_...",
    "update": {
      "sessionUpdate": "agent_message_chunk",
      "content": { "type": "text", "text": "..." }
    }
  }
}
```

映射：

| ACP `sessionUpdate` | 内部动作 |
|---|---|
| `agent_message_chunk` | `sink.delta(text)`，并追加 answer |
| `agent_thought_chunk` | `sink.thinking(text)` |
| `tool_call` | 记录 callId/tool/status，可选发 `tool_status` |
| `tool_call_update` | 更新工具状态 |
| `plan` | 可选记录 |
| `usage_update` | 可选记录 |
| `notice` | 可选日志 |

`session/prompt` 的响应在整轮结束时返回：

```json
{ "stopReason": "end_turn" }
```

此时：

```java
sink.finish(localSessionId, answer.toString());
```

### 4.6 工具调用

Qwen 通过 `session/new.mcpServers` 连接 Gateway 的 MCP Server：

```text
Qwen --MCP tools/call--> Gateway /mcp/{clientId}
Gateway --ACP tool_call--> Client
Client --ACP tool_result--> Gateway
Gateway --MCP content--> Qwen
```

现有 `McpServerController` + `ClientRegistry` 不需要改；只需要把正确的 MCP URL 填进 `mcpServers`。

注意：

- MCP URL 必须能被 Qwen 进程访问（本地 `localhost:8080`）；
- 如果 Gateway 在远程，Qwen 也必须能访问该地址；
- 需要鉴权时在 URL/Header 里加 token（当前没有）。

## 5. 配置

```yaml
agent:
  acp:
    enabled: true
    qwen:
      command: 'D:\nodejs\node_global\qwen.cmd'
      node-path: 'D:\nvm\v22.14.0'
      args: ['--acp']
      auth-type: openai
      api-key: ${AGENT_ACP_API_KEY:}
      base-url: 'https://dashscope.aliyuncs.com/compatible-mode/v1'
      model: qwen-plus
      cwd: ''
      timeout-seconds: 180
      idle-minutes: 30
```

`AcpAgentProfile` 从配置读取；不要把 key 写进代码。

## 6. 与现有 `CliAgentAdaptor` 的关系

保留 `cli`（stream-json）作为对照：

| 适配器 | 协议 | 验证点 |
|---|---|---|
| `cli` | Qwen `--output-format stream-json` | 当前已跑通 |
| `qwen-acp` | `qwen --acp` + JSON-RPC | 新方案 |

`/agents` 会同时出现 `cli` 和 `qwen-acp`；客户端可以指定 `agent=qwen-acp` 做对比。

## 7. 验证步骤

1. **initialize**：确认 Qwen 返回 `protocolVersion=1` 和 capabilities；
2. **session/new**：确认返回 `sessionId`，MCP URL 被接受；
3. **纯文本**：`session/prompt "只回复三个字：ACP测试"`，确认 `agent_message_chunk` 流式到达；
4. **多轮**：同一 session 第二轮，确认上下文延续；
5. **MCP 工具**：注册 `get_name/update_name`，prompt "当前用户是谁"，确认 Qwen 调 MCP、客户端执行、结果回传；
6. **thinking**：如果 Qwen 返回 `agent_thought_chunk`，确认独立通道；
7. **错误**：进程退出/JSON-RPC error，确认转成 `sink.error`；
8. **对比**：同一 prompt 跑 `cli` 和 `qwen-acp`，比较输出、延迟、工具调用。

## 8. 风险与待确认

- Qwen ACP 的认证参数/环境变量在 0.26.0 上仍需实测；`OPENAI_API_KEY` 单独设置时曾报缺少 key；
- `session/new` 的 `mcpServers` 字段格式需要按 Qwen 实际版本验证；
- `session/prompt` 的 ContentBlock 字段需要确认 `type/text` 的准确 schema；
- ACP 没有标准 system prompt，`clientPrompt` 注入是临时方案；
- 权限：本期设置 `fs/terminal=false`，但 Qwen 仍可能发 `session/request_permission`；需要准备默认策略（验证期可 `--approval-mode yolo`，仅限本地临时工作区）；
- 进程复用与并发：同一 session 同时只允许一个 prompt；不同 session 独立进程；
- 远程部署：本地 stdio 不可用，需要后续 ACP WS transport。

## 9. 建议的实施顺序

1. `AcpConnection` + `AcpSession` + `initialize`/`session/new`；
2. `AcpClientAgent.chatStream` + `AcpEventMapper`；
3. 纯文本流式验证；
4. MCP 工具验证；
5. 再补 `session/cancel`、权限、`session/load`；
6. 最后抽象成通用 `AcpAgentProfile`，接 Claude Code/Codex 的 ACP adapter。

## 10. 实施结果（2026-10-27）

已落地：

- `AcpAgent` 统一接口，替代原 `AgentAdaptor`；
- `AcpAgentRegistry`，替代原 `AgentAdaptorRegistry`；
- `AcpConnection`：stdio JSON-RPC；
- `AcpSession`：initialize / session/new / session/prompt / session/update；
- `AcpClientAgent`：注册名 `qwen-acp`；
- `application.yml` 增加 `agent.acp.qwen.*`。

代码位置：

- `AgentAdaptor/src/main/java/com/agent/demo/clientagent/agent_adaptor/AcpAgent.java`
- `AgentAdaptor/src/main/java/com/agent/demo/clientagent/agent_adaptor/AcpAgentRegistry.java`
- `AgentAdaptor/src/main/java/com/agent/demo/clientagent/agent_adaptor/acp/AcpClientAgent.java`
- `AgentAdaptor/src/main/java/com/agent/demo/clientagent/agent_adaptor/acp/AcpConnection.java`
- `AgentAdaptor/src/main/java/com/agent/demo/clientagent/agent_adaptor/acp/AcpSession.java`
- `AgentAdaptor/src/main/java/com/agent/demo/clientagent/agent_adaptor/acp/AcpAgentProfile.java`

验证结果：

| 项 | 结果 |
|---|---|
| `qwen --acp` initialize | 通过，protocolVersion=1 |
| `/agents` 可见 qwen-acp | 通过 |
| 非流式 `/chat` | 通过，返回“ACP测试” |
| SSE `/chat` | 通过，delta 逐段到达，final 正确 |
| MCP 客户端工具 | **未通过**：Qwen 0.26 ACP 不把 MCP 工具加入模型工具表 |

MCP 失败的证据：

- Gateway `/mcp/agent-client` 的 `tools/list` 能返回 `get_name`/`update_name`，`tools/call` 直接调用也成功；
- Qwen ACP 也调用了 Gateway `tools/list`（日志 `[Mcp] 客户端 agent-client 可见工具 2 个`）；
- 但模型侧工具表里没有 `get_name`，调用时返回：
  `Tool "get_name" not found in registry.`
- 用 `tool_search` 搜索 `get_name` 也返回 `Not found: get_name`；
- 试过 `--mcp-config`、`--allowed-mcp-server-names`、`alwaysLoadTools`，均未解决。

结论：文本/流式 ACP 链路已验证；客户端工具链路受 Qwen 0.26 ACP 的 MCP 工具发现限制，属于 Agent 侧问题，不是 Gateway MCP 或 ACP 回调的问题。后续可升级 Qwen Code 或换一个 MCP 工具可见的 ACP Agent 验证；当前客户端工具仍可继续走 `cli` 适配器。

## 参考

- [Gemini CLI ACP Mode](https://geminicli.com/docs/cli/acp-mode/)
- [ACP Overview](https://agentclientprotocol.com/protocol/v1/overview)
- [ACP Initialization](https://agentclientprotocol.com/protocol/v1/initialization)
- [ACP Prompt Turn](https://agentclientprotocol.com/protocol/v1/prompt-turn)
- [ACP Transports](https://agentclientprotocol.com/protocol/v1/transports)
