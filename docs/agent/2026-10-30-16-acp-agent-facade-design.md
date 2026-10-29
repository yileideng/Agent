# AgentGateway 作为 ACP Agent：AcpAgentFacade 设计

> 定位：Gateway 对客户端呈现为一个 **ACP Agent**（ACP v1 线协议），本项目自有的 CAP 从"对外协议"降级为 Facade 的内部管理面
> 状态：**设计完成，P0 未实施**
> 前置：[与 Zed ACP 对齐：协议要点与改造方案](./2026-10-27-13-acp-alignment-plan.md)（差距分析）、[AcpAgent 架构改造技术方案](./2026-10-27-14-acp-agent-architecture-plan.md)（内部 Agent 接口）
> 术语（本文严格遵守）：**ACP** = 本项目的 Agent-Client Protocol（`v/id/type/payload` 信封，HTTP/WS/SSE）；**ACP v1** = Zed Industries 的 Agent Client Protocol（JSON-RPC 2.0，`initialize` / `session/new` / `session/prompt` / `session/update`）。两者不是同一个协议，本文讨论的是"Gateway 实现 ACP v1 的 Agent 侧"。

---

## 1. 结论

三句话：

1. **Gateway 以 ACP v1 的 Agent 身份对外**：一条 WebSocket 连接承载 `initialize → session/new → session/prompt ⇄ session/update`，客户端眼里只有一个 Agent。
2. **客户端工具不再需要"注册"这一步语义**：标准路径由客户端在 `session/new.mcpServers` 里提供 MCP Server；非标准客户端继续调用 `register_tools`，但它此时只是"Facade 的内部管理方法"，不再是 ACP 的组成。
3. **客户端能力（`fs/*`、`terminal/*`）一律不实现**，在能力协商里显式声明不支持。这是取舍，不是缺陷：Facade 是远程服务，客户的本地文件系统不在它可及范围内。

### 1.1 为什么这个定位在 ACP v1 下是合法的

这一点必须先钉死，否则整个设计会被一句"你这不是 ACP"推翻。官方 tool-calls 文档原文：

> **Agents handle the actual execution**, while they may leverage Client capabilities like permission requests or file system access.

以及 session-setup 文档对 `mcpServers` 的定义：

> When creating a session, Clients **MAY** include connection details for MCP servers that the Agent should connect to.

两句话合起来是结论：**`mcpServers` 是客户端"额外"提供给 Agent 的连接信息，不是 Agent 工具的唯一来源**。Agent 完全可以自带工具——而 Gateway 自带的，就是它聚合起来的全部客户端工具。

所以下一段代码事实不构成"协议外挂"，反而是正当实现：

```
客户端 ──register_tools──▶ Gateway（内部管理面，不是 ACP）
                                │
Agent ──/mcp/{clientId}──▶ Gateway 自带的 MCP Server ──▶ 客户端工具
```

`register_tools` 的作用变成"客户端告诉这个 Agent：我这边有哪些工具可用"，和"Agent 自己的工具表怎么维护"是同一层的事，不需要惊动 ACP。

### 1.2 Facade 不是"又一个 Agent 实现"

必须把这句话写进论文，否则贡献会被误读。Facade 的独特之处是**后端可替换**：

| 层次 | 谁 | 职责 |
| --- | --- | --- |
| 协议身份 | Client | 说 ACP v1，只认一个 session |
| **聚合层** | **Facade** | 把 1 个 ACP session 映射到 N 个下游 Agent，切换时保住上下文 |
| 运行时 | 下游 Agent | Self / CLI(Qwen) / Codex / Coze / 外部 ACP Server |

一个 ACP session 内切换下游 Agent、客户端零感知——这是本项目真正可被形式化描述、可量化验证的机制，也是论文第 3 章的落点。

---

## 2. 现状盘点（代码事实，不是设想）

先看清手上有什么，避免把已有能力当成待开发。

| 能力 | 现状 | 位置 |
| --- | --- | --- |
| 对话入口（同步 + SSE） | `POST /chat`，`Accept: text/event-stream` 时发 `session`/`delta`/`thinking`/`final`/`error` | `AgentGateway/.../controller/client/ChatController.java:231`、`:290-357` |
| 公共会话 ID 规范化 | 空值新建，非空补 `clientId:endUserId:` 前缀 | `core/AgentSessionStore.java:79` |
| 共享历史与增量注入 | 切 Agent 时只补该 Agent 未见过的历史 | `core/conversation/ConversationCoordinator.java:86`、`:153` |
| 整轮串行 | 每会话 `turnLock`，工具回调走短锁不阻塞 | `core/conversation/ConversationStore.java:116`、`ConversationCoordinator.java:95` |
| 工具注册 | `POST /clients/register`（`register_tools` 信封） | `controller/client/ToolRegistryController.java:165` |
| 网关代持 MCP Server | `POST /mcp/{clientId}`，支持 `initialize`/`tools/list`/`tools/call`/`ping` | `controller/agent/McpServerController.java:81`、`:98-105` |
| 工具执行路由 | SERVER→HTTP 回调，MOBILE→WS；统一 `execute()` 收口 | `client_registry/ClientRegistry.java:211`、`:270`、`:339` |
| 下游 Agent 抽象 | `AcpAgent`：`chat` / `chatStream` / `close`，**没有 `cancel`** | `acp_agent/AcpAgent.java:24-88` |
| ACP v1 客户端侧（出口）骨架 | `initialize` / `session/new` / `session/prompt`、`session/update` 解析、权限自动放行 | `acp_agent/core/AcpSession.java:47-140`、`:142-213` |
| JSON-RPC over WS 收发骨架 | pending map + id 匹配 + 通知/请求分流 | `acp_agent/core/WsAcpConnection.java:83`、`:159-180` |
| JSON-RPC over WS 服务端样例 | 最小 ACP Server demo（帧透明代理到 `qwen --acp`） | `AcpAgentServer/.../AcpWebSocketHandler.java:99-111` |

**一句话结论：出口侧（Gateway 作为 ACP Client）已有可用骨架，入口侧（Gateway 作为 ACP Agent）从零开始。** 好消息是入口侧最缺的 JSON-RPC 帧处理与请求/通知分流，在 `WsAcpConnection` 和 `AcpWebSocketHandler` 里各有一半现成实现。

### 2.1 相对 ACP v1 的能力缺口

| ACP v1 要求 | 现状 | 缺口性质 |
| --- | --- | --- |
| `initialize` + 版本/能力协商 | 无 | 新增（简单） |
| `session/new` 返回 `sessionId` | 无（客户端自报 sessionId） | 新增，需要会话注册表（中等） |
| `session/prompt` 返回 `stopReason` | 无（`final` 自带 output） | 新增，需要 stopReason 映射（简单） |
| `session/update` 流式 | `delta`/`thinking` 信封 | 协议翻译（简单） |
| `tool_call` / `tool_call_update` | 无上报，只有控制台日志 `[MCP] clientId -> tool` | **新增，需要跨层上下文传递（难）** |
| `session/request_permission` | 无，权限只靠提示词约束 | **新增，跨线程请求-响应（难）** |
| `session/cancel` | 无 | 新增，需给 `AcpAgent` 加 `cancel`（中等） |
| `session/load` 回放 | 共享历史有，回放无 | 新增（中等，P2） |

---

## 3. 方法映射表

### 3.1 Agent 侧（Facade 必须实现）

| ACP v1 方法 | P0/P1/P2 | Facade 行为 | 复用的现有组件 |
| --- | --- | --- | --- |
| `initialize` | P0 | 版本协商（回 1）、声明能力与 `agentInfo` | 无（新增） |
| `session/new` | P0 | 建 `AcpFacadeSession`，回 `sessionId`（+ 可选 `modes`） | `AgentSessionStore.resolveSessionId`、`ConversationCoordinator` |
| `session/prompt` | P0 | 内容块→文本，转 `AgentRequest`，调 `AgentRouter.stream` | `ChatController:290-357` 的 sink 逻辑迁入 `AcpUpdateEmitter` |
| `session/update` | P0 | `delta`→`agent_message_chunk`，`thinking`→`agent_thought_chunk`，工具事件→`tool_call*` | `core/AgentStreamSink` |
| `session/cancel` | P1 | 置取消标志、中断下游、挂起的权限请求回 `cancelled`、prompt 回 `{stopReason:"cancelled"}` | 新增 `AcpAgent.cancel` |
| `session/set_mode` | P1 | `modeId` = 下游 Agent 名，切换后端不换会话 | `AcpAgentRegistry`、`ConversationCoordinator` |
| `session/load` | P2 | 按共享历史回放 `user_message_chunk`/`agent_message_chunk` | `ConversationStore.eventsBetween` |
| `authenticate` / `logout` | P2 | 内网 P0 不启用（`authMethods: []`）；鉴权放传输层握手 | 新增 |
| `session/close` | P2 | 释放 FacadeSession（不影响共享历史） | `AgentSessionStore.clearNativeSession` |

### 3.2 Client 侧（Facade 可能反向发起）

| ACP v1 方法 | P0/P1/P2 | 用途 | 落点 |
| --- | --- | --- | --- |
| `session/request_permission` | P1 | 敏感工具执行前向客户端要授权 | `ClientRegistry.execute` 之前（单一收口点） |
| `fs/read_text_file` / `fs/write_text_file` | 不做 | Facade 无客户端文件系统 | 能力声明为 false |
| `terminal/*` | 不做 | 同上 | 能力声明为 false |
| `elicitation/create` | P2 | 向用户请求结构化输入（如下一步参数） | 未定 |

### 3.3 与现有 CAP 的映射（照抄 §7 的结论，但标注实现归属）

| CAP | ACP v1 | 实现位置变化 |
| --- | --- | --- |
| `register_tools` | 无对应（标准路径改用 `session/new.mcpServers`） | 保留为 Facade 内部方法，见决策 D3 |
| `chat` | `session/prompt` | 新入口，内部仍走 `AgentRouter.stream` |
| `delta` | `agent_message_chunk` | `AcpUpdateEmitter` |
| `thinking` | `agent_thought_chunk` | `AcpUpdateEmitter` |
| `tool_call` / `tool_result` | `tool_call` / `tool_call_update` | `AcpToolCallBridge`（新） |
| `final.output` | `session/prompt` 的响应 `{stopReason}`（正文已在 chunk 里流式给过） | 注意：**不能再指望 final 携带全文** |
| `error` | JSON-RPC error response | 错误码表见 §6.3 |
| `sessionId` | `sessionId`（由 Agent 生成） | `AcpFacadeSessionRegistry` |

---

## 4. 组件设计

### 4.1 新增包 `com.agent.demo.clientagent.acp_facade`

| 类 | 职责 | 关键约束 |
| --- | --- | --- |
| `AcpFacadeWebSocketHandler` | `/acp` 端点；帧→`JsonNode`；按 `method` 分发；响应回写 | 每连接串行写（`synchronized(session)`），避免多线程交错破坏帧 |
| `AcpFacadeSession` | 一个 ACP session 的状态机：`clientId`/`endUserId`/`conversationId`/`currentAgent`/`pendingPrompt`/`pendingPermissions`/`cancelled` | 同一 session 同时只允许 1 个 `session/prompt`（违者 `-32600`） |
| `AcpFacadeSessionRegistry` | `sessionId → AcpFacadeSession`，并绑定到连接 | 断线时保留 session（支持重连后 `session/load`），仅清连接引用 |
| `AcpUpdateEmitter` | 把 `AgentStreamSink` 事件编成 `session/update` 并写帧；管理 `seq`/`messageId` | 必须先发 `session/update` 再完成 `session/prompt` 响应 |
| `AcpToolCallBridge` | MCP `tools/call` 前后合成 `tool_call` / `tool_call_update` | 只能"上报"，不能改变工具执行路径 |
| `AcpPermissionBridge` | 发 `session/request_permission`，阻塞等 outcome，超时即拒绝 | 不得在 `turnLock` 之外持有会话锁等待 |
| `AcpFacadeProperties` | 超时、权限模式、是否暴露 `modes`、是否允许 `register_tools` | 全部走 `application.yml`，默认关闭高风险项 |

### 4.2 与现有组件的调用关系

```
                 ┌──────────────────────────────────────────────┐
   ACP v1 (WS)   │  AcpFacadeWebSocketHandler                    │
  Client ◀──────▶│    ├─ AcpFacadeSessionRegistry                │
                 │    ├─ AcpFacadeSession ── AcpUpdateEmitter ──┐ │
                 │    │                                        │ │
                 │    │  session/prompt                       │ │
                 │    ▼                                        │ │
                 │  AgentRouter ──▶ ConversationCoordinator ──▶ AcpAgent
                 │                    │（turnLock + 增量历史）   （下游：cli/self/coze/acp）
                 │                    ▼
                 │               ConversationStore（共享历史）
                 │
                 │  下行工具链（不变）：
                 │  下游 Agent ──/mcp/{clientId}──▶ McpServerController
                 │                                  ├─ AcpToolCallBridge（上报）
                 │                                  └─ ClientRegistry.execute
                 │                                       ├─ AcpPermissionBridge（拦截）
                 │                                       └─ 客户端 HTTP 回调 / WS
                 └──────────────────────────────────────────────┘
```

### 4.3 一次 prompt turn 的时序

```
Client                          Facade                             下游 Agent
  │  session/prompt                │                                    │
  ├───────────────────────────────▶│ 1 校验 session 存在、无并发 prompt   │
  │                               │ 2 内容块→文本，组 AgentRequest      │
  │                               ├───────────────────────────────────▶│ chatStream
  │                               │                                    │
  │  session/update(agent_thought)│◀─────────── thinking ──────────────┤
  │◀──────────────────────────────┤                                    │
  │  session/update(agent_message)│◀─────────── delta ─────────────────┤
  │◀──────────────────────────────┤                                    │
  │                               │            ┌── MCP tools/call ──────┤
  │                               │◀───────────┘                        │
  │  session/update(tool_call)    │ 3 上报（pending→in_progress）        │
  │◀──────────────────────────────┤                                    │
  │  session/request_permission   │ 4 需要授权时反向请求（P1）           │
  │◀──────────────────────────────┤                                    │
  │  result{outcome:"selected"}   │                                    │
  ├───────────────────────────────▶│                                    │
  │  session/update(tool_call_update completed)                        │
  │◀──────────────────────────────┤                                    │
  │                               │            └── 工具结果回给模型 ────▶│
  │                               │◀─────────── final ──────────────────┤
  │  result{stopReason:"end_turn"}│ 5 落库已在 coordinator 内完成        │
  │◀──────────────────────────────┤                                    │
```

### 4.4 为什么传输选 WebSocket

| 传输 | 判定 | 理由 |
| --- | --- | --- |
| **WS（自定义绑定）** | ✅ P0 | 全双工：`session/request_permission` 是 **Agent 发起的请求**，需要客户端在同一连接上回响应；官方明确允许自定义传输，只要保持 JSON-RPC 与生命周期语义 |
| SSE | ❌ | 单向。`/chat` 的 SSE 只能 Agent→Client 推事件，无法承载"Agent 反向请求 + 客户端回应"的权限流。这是**协议语义**问题，不是实现偏好 |
| stdio | ⚠️ P2 | 只适合"客户端拉起 Agent 子进程"的本地场景。Gateway 是常驻服务，不适合被每个客户端进程拉一次；但可作为桌面客户端的**桥接模式**（见可行性文档 §3.2） |
| Streamable HTTP | ❌ 暂不 | ACP v1 中仍是草案，且网关已有成熟 WS 通道；等稳定后再评估 |

落地方式：Spring `WebSocketConfigurer` 加 `/acp` 端点（现有 `ws/WebSocketConfig.java` 已有 `/ws/agent` 端点可参照），握手阶段用 `Authorization` 头做 token 校验并解析出 `clientId`/`endUserId`——这是 ACP v1 本身没有的东西，必须放在传输层。

---

## 5. 八个设计决策

每个决策给：结论 / 理由 / 落点 / 代价。有分歧的地方写清"为什么不选另一个"。

### D1 传输：WS 单连接

- **结论**：`/acp`，一条 WS 连接，文本帧一条一个 JSON-RPC 消息。
- **理由**：见 §4.4，决定性因素是 `session/request_permission` 需要反向请求。
- **落点**：`AcpFacadeWebSocketHandler`。
- **代价**：不能用流式 HTTP 做负载均衡粘性（需按连接路由到同一实例，或在 session registry 前置 Redis）。

### D2 能力协商的取值

`initialize` 响应（P0）：

```json
{
  "protocolVersion": 1,
  "agentCapabilities": {
    "loadSession": false,
    "promptCapabilities": { "image": false, "audio": false, "embeddedContext": false },
    "mcpCapabilities": { "http": true, "sse": false }
  },
  "agentInfo": { "name": "agent-gateway", "title": "Agent Gateway", "version": "0.1.0" },
  "authMethods": []
}
```

- `mcpCapabilities.http = true` 的含义是"**我（Facade）能连** HTTP MCP Server"——用于 P1 承接客户端在 `session/new` 里传的 `mcpServers`。
- `promptCapabilities` 全 false，意味着客户端只能发 `text` 与 `resource_link`（基线强制项）。P1 再做 `embeddedContext`（把 `resource` 块转成文本注入）。
- `loadSession=false` 时客户端**禁止**调 `session/load`，所以 P0 必须先诚实声明，等 P2 实现后再改 true。

### D3 客户端传来的 `mcpServers` 怎么处理（三层策略）

这是最容易出错的地方：规范说 Agent **SHOULD** 连接客户端给的所有 MCP Server，而 Gateway 的模型是"我自己代持一个 MCP"。两者不是天然兼容。

| 层 | 输入 | 处理 | 阶段 |
| --- | --- | --- | --- |
| 标准路径 | `session/new.mcpServers`（stdio/http/sse） | 合并进下游 ACP Agent 的 `session/new.mcpServers`；下游是 CLI 类则改写 `--mcp-config`，合并失败明确报错 | P1 |
| 标准路径（兜底） | 同上但下游不支持 | 不静默丢弃：在 `session/new` 结果 `_meta.warnings` 里列明，并在 `session/update` 里发一条 `notice`（若客户端声明了 `session.notices` 能力） | P1 |
| 扩展路径 | `register_tools`（HTTP，非 ACP） | 网关代持 MCP（现状），下游 Agent 连 `/mcp/{clientId}` | P0 已有 |

**冲突处理**：同名 MCP Server 以内置 `client-tools` 为准，客户端传入的同名项改名 `<name>-client` 并告警（`AcpSession.mcpServers()` 目前 `use-mcp-config-file=true` 时直接清空，避免同名冲突，见 `AcpSession.java:54-60`）。

### D4 会话映射：`acpSessionId` 直接复用内部 canonical sessionId

- **结论**：`session/new` 返回的 ID 就用 `AgentSessionStore.resolveSessionId(clientId, endUserId, null)` 的结果，形如 `wx-demo:demo-user:3f2c8e...`。ACP sessionId 是不透明字符串，暴露内部结构不违规。
- **理由**：这是唯一能让**工具回调**与 **ACP session** 天然对齐的方案。下游 Agent 的 MCP URL 由 `AcpSession.mcpServerUrl()` 拼出，形如 `/mcp/{clientId}?endUserId=..&sessionId=..`（`AcpSession.java:251-271`）。若 ACP sessionId 与内部 sessionId 一致，`tools/call` 一进来就能反查 `AcpFacadeSession`，不需要额外映射表，也不会串号。
- **代价**：泄露租户/用户标识的结构；若将来 `clientId` 需要轮换，会话 ID 会不稳定。
- **补救**：`AcpFacadeSessionRegistry` 内部维护 `acpSessionId → session`；P1 可换成随机 token 并在 registry 里保存反向映射，工具回调改为查表。届时 MCP URL 的 `sessionId` 参数仍传内部 ID（不对外），ACP ID 只写在协议层。

### D5 `tool_call` 上报的合成

ACP v1 要求 Agent 自己执行工具并上报，但本项目的工具执行在客户端。所以 Facade 是"**代下游 Agent 上报**"：它知道下游让 MCP 调了什么，但它不是这次调用的发起者。

- **合成点**：`McpServerController.toolsCallResult()`（`:146`）——MCP `tools/call` 的**唯一入口**，天然包含 `name` / `arguments` / `sessionId`（查询参数）。
- **联动方式**：`ClientRegistry.execute()` 内已经生成 `callId`（`newContext()`，`:73-79`）。把它作为 `toolCallId` 直接复用，`MCP call ↔ ACP toolCallId` 一一对应，避免两套 ID。
- **为什么不在 `ClientRegistry` 里直接发**：`ClientRegistry` 同时服务 CAP 与 ACP 两条入口，不该知道 WS 帧长什么样。它应该只发布一个内部事件，由 `AcpToolCallBridge` 订阅后翻译。
- **状态映射**：

| 时刻 | ACP `status` |
| --- | --- |
| MCP 收到 `tools/call`、尚未执行 | `pending` |
| 开始执行（权限通过后） | `in_progress` |
| 结果正常 | `completed` + `content` |
| 异常 / 客户端返回 error | `failed` |

- **`kind` 映射**：按工具名/描述关键词给出 `read`（`get_*`/`read_*`/`list_*`）、`edit`（`set_*`/`add_*`/`update_*`/`connect_*`）、`delete`（`remove_*`/`delete_*`）、`execute`（`run_*`）、其余 `other`。**允许客户端的工具定义显式声明 `kind`**（`ToolDefinition` 加字段），比猜名字可靠。
- **`content` 包装**：MCP 的 `content[{type:text,text}]` → ACP 的 `content[{type:"content",content:{type:"text",text}}]`。注意名字重复是规范要求，不是笔误。
- **已知限制**：`tool_call` 的 `rawInput`/`rawOutput` 直接把 MCP 的 `arguments` 与结果文本塞进去，**可能包含敏感数据**（用户输入、画布内容）。默认关闭 `rawOutput`，只发 `content` 的简短摘要；`rawInput` 保留（客户端本来就知道参数）。

### D6 权限拦截（`session/request_permission`）

- **插入点**：`ClientRegistry.execute()` 内、`appendToolCall` 之前（`:218-224`）。理由是它是 SERVER/MOBILE 两条传输的唯一收口，且已经持有 `ToolCallContext`（含 sessionId/chatId/callId）。
- **流程**：`execute()` → `AcpPermissionBridge.request(context, tool, arguments)` → 发 `session/request_permission` → 阻塞等 outcome（默认 30s）→ 超时/断线/`cancelled` 一律视为拒绝 → 拒绝时抛 `ToolDeniedException`，`execute()` 按失败路径落库并把 `isError` 回给 MCP。
- **权限模式**（配置 `agent.permission.mode`）：

| 模式 | 行为 | 用途 |
| --- | --- | --- |
| `prompt` | 每个工具都问客户端 | 最安全，答辩演示合适 |
| `allow-list` | 按 `kind` 白名单（`read`/`search` 直接放行，`edit`/`delete`/`execute` 询问） | **默认推荐** |
| `yolo` | 全部放行（仅记日志） | 本地调试 |

- **死锁分析**（必须写清，否则实现时会踩）：工具调用线程是 MCP HTTP 请求线程；权限请求写在 WS 线程，响应由 WS 读线程处理——两者不同线程，不会自锁。真正的风险是**客户端**：如果客户端在等 `session/prompt` 的 HTTP 响应期间不处理 WS 消息，就会互相等待。所以协议文档要给客户端一条硬要求：**必须并发处理入站 JSON-RPC 消息**（`session/update` 与 `session/request_permission` 都不能等 prompt 返回）。
- **幂等/记忆**：`allow_always` / `reject_always` 由 Facade 记忆（规范说"Agent owns remembered decisions"），作用域限定为 `(sessionId, toolName, kind)`，并在 `session/close` 时清空。

### D7 `cwd` 的语义偏离

ACP v1 要求 `cwd` 是绝对路径，且 Agent **MUST** 用它作为相对路径基准与工作区根集合。对远程 Facade 这是不可能满足的：客户端的 `/home/user/project` 在网关机器上不存在。

- **结论**：接收 `cwd`，但按下游能力分三档处理：
  1. 下游为**同机** ACP Agent/CLI：直接透传 `cwd`（`AcpSession.prepareWorkDir()` 目前自造目录，需加分支）；
  2. 下游为远程服务（如 Coze/Brain）：忽略，改用网关工作目录 `data/acp-sessions/{sessionId}`；
  3. 无论哪种，都在 `session/new` 响应里带 `_meta.cwdRemapped=true` 与 `_meta.effectiveCwd`，让客户端知道真实值。
- **必须承认**：这是对规范的**已知偏离**，不是实现细节。论文里写"远程 Agent 网关下 `cwd` 语义的降级与显式告知"，比假装支持更站得住。

### D8 取消（`session/cancel`）

- **结论**：`session/cancel` 是通知（无 id），Facade 收到后：置 `session.cancelled=true` → 中断下游 → 停止 `AcpUpdateEmitter` → 把挂起的权限请求回 `{"outcome":{"outcome":"cancelled"}}` → `session/prompt` 响应 `{"stopReason":"cancelled"}`。
- **需要的接口改动**：`AcpAgent` 目前没有 `cancel`（`AcpAgent.java:24-88`）。加 `default void cancel(String sessionId) {}`，由 CLI 适配器实现"杀进程"、ACP 适配器实现"发 `session/cancel` + 关连接"。
- **P0 的降级**：先做"标记取消 + 立即回 `cancelled` + 丢弃后续 delta"，下游继续跑完但结果不入库（`ConversationCoordinator` 的 `closed` 标志已能阻止二次 finish，`:114-124`）。P1 再做真正中断。
- **为什么不能只关连接**：`session/cancel` 的语义是"停这一轮"，不是"结束会话"；客户端可能马上发下一轮。

---

## 6. 关键机制

### 6.1 三类 ID 的关系

| ID | 谁生成 | 作用域 | 谁在用 |
| --- | --- | --- | --- |
| `sessionId`（ACP） | `session/new` | 一个会话 | 客户端、FacadeSession |
| `chatId` | Facade 每轮生成 | 一轮 | `ConversationStore` 事件归属；**不进 ACP 协议** |
| `toolCallId` | 复用 `ToolCallContext.callId` | 一次工具调用 | MCP `tools/call` ↔ ACP `tool_call` |

`chatId` 不进协议是刻意的：ACP v1 没有这个概念，把它塞进 `_meta` 只会制造第二个真相来源。

### 6.2 事件顺序不变量

实现时按这几条自检，任何一条被破坏都是协议层 bug：

1. `initialize` 必须先于 `session/new`；未初始化就调 session 方法 → `-32600`。
2. `session/new` 之前不得发任何 `session/update`。
3. 同一 session 同时只能有一个进行中的 `session/prompt`；第二个并发请求 → `-32600`（不是排队，避免客户端误以为并发安全）。
4. `session/update` 必须先于对应的 `session/prompt` 响应；响应之后不得再为该轮发 `update`。
5. `tool_call` 必须先于同 `toolCallId` 的 `tool_call_update`。
6. 取消后，`session/prompt` 必须回 `cancelled`，且不得再发 `agent_message_chunk`。

### 6.3 错误码映射

| 场景 | JSON-RPC error |
| --- | --- |
| 未 `initialize` 就调方法 / 并发 prompt / 参数结构错 | `-32600` Invalid Request |
| 未知 method | `-32601` Method not found |
| 参数缺失（如 `prompt` 为空、`cwd` 非绝对路径） | `-32602` Invalid params |
| 下游 Agent 不可用 / 执行异常 | `-32603` Internal error（message 带原因） |
| 未授权（P2 若启用 `authenticate`） | `-32000`（`auth_required`） |
| `sessionId` 不存在 | `-32002`（`resource not found`，规范对未知会话的约定） |

**不要**把业务失败塞进 JSON-RPC error：工具执行失败是 `tool_call_update.status=failed` + `content`，不是 RPC 错误——否则客户端会以为整轮崩了。

### 6.4 落库与 `final` 的关系

现有 `ConversationCoordinator.stream()` 在 `finish` 时把完整回复落库并发 `final`（`:116-124`）。ACP 下没有 `final` 事件，正文全部在 `agent_message_chunk` 里，`session/prompt` 的响应只有 `stopReason`。因此：

- `AcpUpdateEmitter` 需自行累积 `answer` 文本，在 `session/prompt` 响应**之前**保证累积完成；
- `ConversationCoordinator` 的 `finish(sessionId, output)` 回调保持原样，由 emitter 把 `output` 丢掉（它与累积值等价），只取 `sessionId` 与完成信号；
- 若下游中途失败，`error` 路径仍走 `appendFailure`，但**不**发 `session/update error`（ACP 没有 error update），而是在 `session/prompt` 响应里回 `-32603`，并在 `_meta` 里带上 `code`。

---

## 7. 最小验证方案（P0 验收）

### 7.1 最小 ACP 客户端

不引第三方 ACP SDK，写一个约 150 行的单文件客户端（Node，`ws` 库即可），因为要验证的是**网关的协议正确性**，不是客户端库的兼容性。

必须实现的行为：

1. 连 `/acp`，发 `initialize`（`protocolVersion:1`），校验响应版本与 `agentCapabilities`；
2. `session/new`（`cwd` 用绝对路径），拿到 `sessionId`；
3. `session/prompt` 发一段中文，**同时**收集所有 `session/update`，收到响应后断言 `stopReason` 与累积文本非空；
4. 注册一个假工具（走 `register_tools`），触发模型调用，断言收到 `tool_call` 与 `tool_call_update(completed)`；
5. 发 `session/cancel`，断言响应为 `cancelled`；
6. 故意发未初始化方法，断言 `-32600`。

建议脚本化：`scripts/acp-smoke.js`，一条命令跑完 6 个场景，输出"通过/失败"清单。

### 7.2 验收用例

| # | 用例 | 通过标准 |
| --- | --- | --- |
| 1 | 版本协商 | 客户端发 `1` 得到 `1`；发 `99` 得到 `1`（Facade 只能回自己支持的版本） |
| 2 | 未初始化调用 | `session/prompt` 在 `initialize` 前 → `-32600` |
| 3 | 未知方法 | `foo/bar` → `-32601` |
| 4 | 建会话 | 返回非空 `sessionId`，且与内部 canonical ID 一致（可查日志） |
| 5 | 流式对话 | 收到 ≥1 个 `agent_message_chunk`，`stopReason=end_turn`，文本完整 |
| 6 | 工具往返 | 收到 `tool_call(pending)` → `tool_call_update(in_progress)` → `completed`，且 `toolCallId` 与 MCP 日志一致 |
| 7 | 权限拒绝（P1） | 客户端回 `reject_once`，工具未执行，模型收到 isError |
| 8 | 取消 | `session/cancel` 后 prompt 回 `cancelled`，无后续 chunk |
| 9 | 并发 prompt | 第二个并发请求 → `-32600` |
| 10 | 断线重连 | 重连后旧 `sessionId` 行为符合声明（P0 `loadSession=false` 时应返回 `-32002`） |

### 7.3 与现有客户端的联调顺序

1. 先用最小客户端（§7.1）打网关，**不碰任何现有客户端**——这是隔离变量；
2. 再把 Java 版 `Client` 模块的 `ClientChatController` 改成 ACP 客户端，验证 Java 侧兼容；
3. 最后才评估 wx-demo（C++），是否改由可行性文档决定（结论是"先用 stdio 桥接，不原地重写"）。

---

## 8. 分阶段与工作量

| 阶段 | 内容 | 涉及文件 | 估时 |
| --- | --- | --- | --- |
| **P0** | `/acp` WS 端点、`initialize`/`session/new`/`session/prompt`/`session/update`、`tool_call` 上报、错误码、最小客户端与 6 条验收 | 新增 `acp_facade/*`（约 900–1200 行）；改 `McpServerController`（+30）、`ClientRegistry`（+40，事件发布）、`application.yml`（+10） | **4–6 人日** |
| **P1** | 权限拦截（`request_permission` + 模式 + 记忆）、`session/cancel` 真中断（`AcpAgent.cancel`）、`session/set_mode` 切 Agent、`mcpServers` 合并 | 新增 `AcpPermissionBridge`（250）、改 `AcpAgent`/`AgentRouter`/3 个适配器（+150）、`ToolDefinition`（+10） | **4–5 人日** |
| **P2** | `session/load` 回放、`register_tools` 迁到 ACP 方法（`_gateway/register_tools`）、stdio 桥接、鉴权 | 新增回放（200）、`authenticate`（150）、桥接 CLI（300） | **5–8 人日** |

**P0 是分水岭**：它把"Gateway 是 ACP Agent"从主张变成可演示事实，且不改任何现有客户端。P1 才开始动内部接口。

---

## 9. 风险

| # | 风险 | 影响 | 应对 |
| --- | --- | --- | --- |
| R1 | `tool_call` 上报依赖 MCP 请求里的 `sessionId`，一旦下游 Agent 不按约定带参数，事件无法归属 | 工具事件丢失或串号 | 上报失败只记日志、不阻断工具执行；并加一条"无 sessionId 时按连接最近一次 prompt 归属"的兜底 |
| R2 | 客户端不并发处理入站消息 → 权限请求死等 | 工具调用超时 | 协议文档写死要求；`request_permission` 超时 30s 后按拒绝处理，不让整轮挂死 |
| R3 | 下游 Agent 的 `session/update tool_call` 被丢弃（`AcpSession.java:168-176` 只打日志） | ACP 客户端看不到工具进度 | P1 时把下游 `tool_call` 映射为 Facade 的 `tool_call_update`（复用下游 `toolCallId` 加前缀） |
| R4 | 远程 `cwd` 偏离规范 | 严格客户端可能拒绝 | 用 `_meta.cwdRemapped` 显式告知；论文如实写明 |
| R5 | WS 粘性/多实例下 session registry 只在内存 | 扩容后串话或找不到 session | P2 前置说明：单实例或 Redis 共享；当前 demo 单实例 |
| R6 | 把 `tool_call` 的 `rawInput` 原样透传 | 泄露用户数据 | 默认只发 `content` 摘要，`rawInput` 需显式开启 |

---

## 10. 附录：关键消息样例（P0）

**initialize**

```json
{"jsonrpc":"2.0","id":0,"method":"initialize","params":{
  "protocolVersion":1,
  "clientCapabilities":{"fs":{"readTextFile":false,"writeTextFile":false}},
  "clientInfo":{"name":"wx-demo","title":"Circuit Canvas","version":"0.1"}
}}
```

**session/new 响应**

```json
{"jsonrpc":"2.0","id":1,"result":{
  "sessionId":"wx-demo:demo-user:3f2c8e40-...",
  "_meta":{"effectiveCwd":"data/acp-sessions/3f2c8e40","cwdRemapped":true}
}}
```

**session/update（工具调用完成）**

```json
{"jsonrpc":"2.0","method":"session/update","params":{
  "sessionId":"wx-demo:demo-user:3f2c8e40-...",
  "update":{
    "sessionUpdate":"tool_call_update",
    "toolCallId":"9b1f...",
    "status":"completed",
    "content":[{"type":"content","content":{"type":"text","text":"已添加 AND 门到 (120,200)"}}]
  }
}}
```

**session/prompt 响应**

```json
{"jsonrpc":"2.0","id":2,"result":{"stopReason":"end_turn"}}
```

---

## 参考

- [ACP v1 Overview](https://agentclientprotocol.com/protocol/v1/overview)
- [Initialization](https://agentclientprotocol.com/protocol/v1/initialization)（`protocolVersion` 为整数、能力省略即不支持）
- [Session Setup](https://agentclientprotocol.com/protocol/v1/session-setup)（`cwd` 必须为绝对路径；`mcpServers` 是客户端**可选**提供）
- [Prompt Turn](https://agentclientprotocol.com/protocol/v1/prompt-turn)（`session/update` 变体、`stopReason`、取消语义）
- [Tool Calls](https://agentclientprotocol.com/protocol/v1/tool-calls)（`status`/`kind`/`content`、`request_permission` 四种选项）
- [Session Modes](https://agentclientprotocol.com/protocol/v1/session-modes)（`modes` + `session/set_mode`，注意官方已提示将转向 config options）
