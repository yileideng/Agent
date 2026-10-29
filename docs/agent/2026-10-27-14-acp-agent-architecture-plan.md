# AcpAgent 架构改造技术方案

> 目标：把“协议适配”和“Agent 注册”收敛成一套接口，支持 ACP Agent、外部 ACP Server 和内部 Adaptor
> 状态：**Phase 1/2 已实施；AgentRouter 与 WS-only 外部注册已落地；文本/流式通过，MCP 工具受 Qwen 0.26 ACP 限制**
> 相关：[Qwen ACP 验证方案](./2026-10-27-13-qwen-acp-validation-plan.md)、[ACP 对齐方案](./2026-10-27-13-acp-alignment-plan.md)

![Agent 网关架构](../assets/agent-gateway-architecture.png)

## 1. 结论

不要引入两套平行抽象。推荐：

> **一个 `AcpAgent` 统一运行时接口 + 一个 `AcpAgentRegistry`；ACP Agent 由通用 `AcpClientAgent` 接入，内部 Agent 由 `AgentAdaptor implements AcpAgent` 接入。**

`AgentAdaptor` 是 `AcpAgent` 的实现方式，不是另一个注册体系。

## 2. 两个概念必须拆开

### 2.1 `AcpAgent` 是网关内部接口

它描述“网关怎么调用一个 Agent”，不是 ACP wire protocol：

```java
public interface AcpAgent {
    String id();
    AgentCapabilities capabilities();
    boolean available();

    AgentResponse chat(AgentRequest request);
    void chatStream(AgentRequest request, AgentStreamSink sink);

    default void cancel(String sessionId) {
    }
}
```

- 客户端不实现它；
- ACP Agent 也不实现它；
- 它只服务于 Gateway 内部路由。

### 2.2 ACP 线协议由 `AcpClientAgent` 适配

```text
Qwen --acp / Claude ACP / Codex ACP
        │ ACP JSON-RPC over stdio/WS
        ▼
AcpClientAgent（一个通用 Java 实现）
        │ implements
        ▼
     AcpAgent
```

`AcpClientAgent` 负责：

- 启动/连接 ACP Agent；
- `initialize`、`authenticate`；
- `session/new`、`session/prompt`、`session/cancel`；
- 把 `session/update` 转成内部事件；
- 处理 `session/request_permission`；
- 会话/进程生命周期。

它不是“每个 Agent 一个”，而是“每个传输/协议一个”。

## 3. 当前代码与目标差距

当前：

- `AgentAdaptor`：`chat/chatStream`；
- `AgentAdaptorRegistry`：Spring Bean 列表；
- `CliAgentAdaptor`：Qwen stream-json；
- `SelfAgentAdaptor`、`CozeAgentAdaptor`：内部协议适配；
- 没有外部 Agent descriptor，没有 ACP Client。

目标：

```text
AcpAgentRegistry
├── AcpClientAgent        （ACP Agent，通用）
├── SelfAgentAdaptor      （内部 Brain，implements AcpAgent）
├── CozeAgentAdaptor      （Coze，implements AcpAgent）
└── CliAgentAdaptor       （stream-json，保留对照）
```

`/chat` 仍走 ACP；Gateway 通过 `AcpAgent` 选择 Agent，不要求客户端理解 ACP。

## 4. 核心接口

### 4.1 AcpAgent

```java
public interface AcpAgent extends AutoCloseable {
    String id();

    AgentCapabilities capabilities();

    default boolean available() {
        return true;
    }

    AgentResponse chat(AgentRequest request);

    void chatStream(AgentRequest request, AgentStreamSink sink);

    default void cancel(String sessionId) {
    }

    @Override
    default void close() {
    }
}
```

- `AgentRequest` 复用现有实现：`clientId/endUserId/prompt/clientPrompt/sessionId/chatId`；
- `AgentStreamSink` 复用：`delta/thinking/finish/error`；
- 后续如需 ACP 全量语义，再扩展 `toolCall/toolUpdate/permission`，不要一开始就重写。

### 4.2 AcpAgentDescriptor

```java
public record AcpAgentDescriptor(
        String agentId,
        Source source,          // INTERNAL / EXTERNAL
        Protocol protocol,      // ACP / CLI / HTTP / COZE
        Transport transport,    // STDIO / WS / HTTP
        String command,         // stdio: qwen
        List<String> args,      // ["--acp"]
        String endpoint,        // ws/http
        Map<String, String> env,
        AgentCapabilities capabilities,
        AuthConfig auth,
        HealthConfig health
) {
}
```

### 4.3 AcpAgentRegistry

```java
public interface AcpAgentRegistry {
    Optional<AcpAgent> get(String agentId);

    List<AcpAgentInfo> list();

    void register(AcpAgentDescriptor descriptor);

    void unregister(String agentId);
}
```

- 内部 Adaptor：Spring Bean 启动时注册；
- 外部 ACP Agent：管理员 API 注册 descriptor，按需创建 `AcpClientAgent`；
- Registry 不持有进程，只持有 descriptor/factory。

## 5. 三种接入方式

### 5.1 原生 ACP Agent

例如 Qwen Code：

```yaml
agentId: qwen-acp
protocol: ACP
transport: STDIO
command: qwen
args: ["--acp"]
```

- 不需要写新 Java 类；
- `AcpClientAgent` 读取 descriptor，启动 `qwen --acp`；
- 通过 `initialize` 协商能力；
- 通过 `session/new.mcpServers` 接 Gateway MCP。

### 5.2 外部 ACP-adapted Agent

例如 Claude Code / Codex 的 ACP adapter：

```yaml
agentId: claude-acp
protocol: ACP
transport: STDIO
command: claude-agent-acp
```

或远程：

```yaml
agentId: remote-acp
protocol: ACP
transport: WS
endpoint: wss://agent.example.com/acp
```

- 同样用 `AcpClientAgent`；
- 不需要为每个 Agent 写 Adaptor；
- 远程传输需要自定义 ACP binding（ACP 目前 stdio 为主）。

### 5.3 内部 Agent

例如 Self Brain、Coze、非 ACP CLI：

- 写一个 `AgentAdaptor implements AcpAgent`；
- 在适配器内部完成私有协议 → `AgentRequest`/`AgentStreamSink` 的转换；
- 注册到 `AcpAgentRegistry`。

```java
@Component
public class SelfAgentAdaptor implements AcpAgent {
    public String id() { return "self"; }
    public void chatStream(AgentRequest request, AgentStreamSink sink) {
        // Brain HTTP -> delta/final
    }
}
```

## 6. 一次完整请求

```text
1. Client → Gateway: ACP chat
2. Gateway: AcpAgentRegistry.get(agent)
3. Gateway → AcpAgent: chatStream(request, sink)
4. AcpAgent → Agent: 协议转换（ACP/ACP/HTTP/CLI）
5. Agent → AcpAgent: 厂商事件
6. AcpAgent → Gateway: delta/thinking/final/error
7. Gateway → Client: ACP SSE
```

工具调用：

```text
Agent → MCP tools/call → Gateway /mcp/{clientId}
Gateway → Client: tool_call（HTTP/WS）
Client → Gateway: tool_result
Gateway → Agent: MCP content
```

## 7. Registry 与安全

外部 Agent 注册不是普通客户端能力：

- 只有管理员/受信服务可以注册；
- `command` 必须 allowlist，不能让任意人注册任意命令；
- 外部 Agent 要有 auth、health、version、capabilities；
- 外部 stdio Agent 只能在受控本地/沙箱运行；
- 远程 ACP Agent 必须限制 endpoint/网络访问；
- 注册失败/不可用要能降级到内置 Agent。

## 8. 渐进改造步骤

### Phase 1：接口收敛

- 把 `AgentAdaptor` 抽象提升为 `AcpAgent`（可保留旧名作为别名，避免大改）；
- `AgentAdaptorRegistry` 增加 descriptor/registry 能力；
- 现有 Self/Coze/Cli 适配器实现 `AcpAgent`；
- `/agents` 从 Registry 读。

### Phase 2：Qwen ACP 验证

- 新增 `AcpClientAgent`（stdio）；
- descriptor `qwen-acp`；
- 跑通 `initialize → session/new → session/prompt → session/update`；
- 通过 `session/new.mcpServers` 接现有 MCP 工具。

### Phase 3：外部注册

- 增加管理员注册 API；
- descriptor 校验、命令 allowlist、健康检查；
- 外部 ACP Agent 按需创建 `AcpClientAgent`。

### Phase 4：远程/权限/取消

- ACP WS transport；
- `session/request_permission` 接入客户端确认；
- `session/cancel`；
- `session/load`。

## 9. 验证标准

- Qwen `--acp` 能完成 initialize/session/new/prompt；
- 文本流式到达；
- 同一会话多轮；
- MCP 工具调用成功；
- 失败时 `sink.error`，不泄漏 JSON-RPC 细节；
- `cli` 与 `qwen-acp` 可切换对比；
- 外部 descriptor 注册/注销/不可用降级。

## 10. 风险

- `AcpAgent` 命名容易被误解为 ACP wire；文档必须注明“网关内部接口”；
- `AgentAdaptor` 与 `AcpAgent` 如果处理不好会变成两套；
- 外部注册命令执行有 RCE 风险；
- ACP stdio 本地进程模型与远程 Gateway 有冲突；
- 系统提示词、客户端记忆、工具面仍然是独立问题，不要塞进 `AcpAgent` 接口。

## 11. 建议

先做 Phase 1 + Phase 2：

- 用 `AcpAgent` 统一现有 Self/Coze/Cli；
- 用 `AcpClientAgent` 接 Qwen `--acp`；
- 保持 ACP 客户端不变；
- 验证通过后再决定是否开放外部注册。

## 12. 已实现：AgentRouter 与外部注册

### 12.1 AgentRouter

新增 `acp_agent.AgentRouter`：

- `resolve(agentId)`：从 `AcpAgentRegistry` 取 Agent，并检查 `available()`；
- `chat/stream`：按 agentId 路由；
- `ChatController` 不再直接依赖 `AcpAgentRegistry`；
- 未知/不可用 Agent 返回 ACP `AGENT_UNAVAILABLE`。

### 12.2 外部 ACP Agent 注册（简化版）

核心可以很轻：

> 外部 Agent 注册时给一个 WS 地址，`AcpClientAgent` 连上去，按 ACP JSON-RPC 调用即可。

没必要为一个 WS 地址再拆出 `AcpRemoteAgent`、`InternalAgentProfile` 等一整套类。

#### 外部注册 descriptor

```json
{
  "agentId": "remote-qwen",
  "wsUrl": "wss://agent.example.com/acp",
  "authToken": "optional-bearer-token",
  "mcpUrl": "https://gateway.example.com/mcp"
}
```

- 没有 `command`、`args`、`env`、`workDir`；
- 外部服务自己负责 Agent 进程；
- Gateway 只负责连接。

#### 代码结构

```text
AcpClientAgent
  └── AcpConnection（接口）
      ├── StdioAcpConnection   // 内置 Qwen 等本地进程
      └── WsAcpConnection      // 外部 WS Server
```

`AcpSession` 继续复用 `AcpConnection` 的 `request/notify/respond` 接口；只是把现在的 stdio 实现抽成接口，加一个 WS 实现。

不需要：

- `AcpRemoteAgent` 单独类；
- `InternalAgentProfile` / `ExternalAgentRegistration` 双 descriptor；
- 外部注册后再在 Gateway 本地启动进程。

#### 已实现

1. `AcpConnection` 抽成接口（`request` / `onNotification` / `onRequest` / `respond` / `close`）；
2. 现有 stdio 逻辑改名为 `StdioAcpConnection`；
3. 新增 `WsAcpConnection`：
   - 连接 `ws://` / `wss://`；
   - 每个 WebSocket text frame 放一条 JSON-RPC；
   - 复用 `AcpSession` 的 initialize/session/prompt/update；
4. `AcpClientAgent` 根据 profile 是否有 `wsUrl` 选择 `Stdio` 或 `Ws`；
5. `/admin/agents` 只接收 `agentId/wsUrl/authToken/mcpUrl`，没有 `command/args`。

验证结果：

- 内置 `qwen-acp` 文本对话仍通过；
- `POST /admin/agents` 注册 WS Agent 返回 201；
- `/agents` 能看到外部的 `available=true`；
- 没有 WS Server 时 `/chat` 返回 `ACP_ERROR: 连接 ACP WS 失败`，不会退回本地启动；
- 删除外部 Agent 返回 204。

本地 Qwen Code WS Server（`AcpAgentServer`）联调已通过：

- AcpAgentServer 启动后自动注册 `ws://localhost:8095/acp` 到 `http://localhost:8080`；
- 非流式返回“WS测试”；
- SSE delta/final 正常；
- 同一 session 多轮可延续上下文。

详见 [AcpAgentServer 验证服务](./2026-10-27-15-acp-agent-server.md)。

之前写的“外部/内置双 descriptor + RemoteAgent”属于过度设计，不采用。
