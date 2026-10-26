# 与 Zed ACP 对齐：协议要点与改造方案

> 状态：**备选参考，不强制实施**；当前仍以 CAP 为主
> 目标：评估项目客户端协议与 [Agent Client Protocol v1](https://agentclientprotocol.com/protocol/v1/overview) 的差距，给出可分阶段落地的改造方案
> 结论：**不要把现有 CAP 直接改名为 ACP；建议双栈，先做 ACP 适配，再逐步把 ACP 作为正式客户端协议**

## 1. ACP v1 是什么

ACP 是 **Client ↔ Agent** 的协议，不是工具协议：

- **Client**：编辑器、自定义 App、后端服务、移动端；负责 UI、工作目录、文件、终端、权限确认；
- **Agent**：编码智能体；负责模型推理、工具调用、会话状态；
- **MCP**：继续负责 Agent ↔ 工具，ACP 不替代 MCP。

通信模型：

- JSON-RPC 2.0，UTF-8；
- **stdio 是主传输**：Client 启动 Agent 子进程，按行收发 JSON-RPC；
- Streamable HTTP 仍是草案；
- 允许自定义传输，但必须保持 JSON-RPC 消息格式和生命周期语义。

## 2. 方法一览

### Agent 侧

| 方法 | 必选 | 作用 |
|---|---|---|
| `initialize` | 是 | 协商协议版本、能力、认证方式 |
| `authenticate` | 条件 | Agent 要求认证时调用 |
| `session/new` | 是 | 创建会话，传 `cwd`、`mcpServers` 等 |
| `session/prompt` | 是 | 发送一轮用户输入，返回 `stopReason` |
| `session/load` | 否 | 恢复历史会话（需 `loadSession` 能力） |
| `logout` | 否 | 退出认证 |
| `session/set_mode` | 否 | 切换 Agent 模式 |
| `session/cancel` | 是（通知） | 取消当前处理 |

### Client 侧

| 方法 | 必选 | 作用 |
|---|---|---|
| `session/request_permission` | 是 | Agent 请求用户批准敏感工具调用 |
| `fs/read_text_file` | 否 | Agent 读取 Client 工作区文件 |
| `fs/write_text_file` | 否 | Agent 写 Client 工作区文件 |
| `terminal/*` | 否 | Agent 在 Client 终端执行命令 |
| `elicitation/create` | 否 | 向用户请求结构化信息 |
| `session/update` | 是（通知） | Agent 推送流式内容、工具状态、计划、用量等 |
| `elicitation/complete` | 否 | 通知 URL 交互完成 |

## 3. 一次 Prompt Turn

```text
Client → Agent: initialize
Client → Agent: session/new
Client → Agent: session/prompt
Agent → Client: session/update (plan / agent_message_chunk / tool_call / ...)
Agent → Client: session/request_permission（可选）
Client → Agent: permission outcome
Agent → Client: session/update (tool_call_update completed)
Agent → Client: session/prompt response { stopReason }
```

关键点：

- `session/update` 是通知，不是请求；`sessionUpdate` 字段区分变体；
- `session/prompt` 的响应只在整轮结束时返回，带 `stopReason`；
- 权限是 **Agent 反向请求 Client**，Client 持有执行入口；
- 文件路径必须是绝对路径；
- `initialize` 的协议版本是整数（当前 `1`），能力省略视为不支持。

## 4. 本项目当前差距

| 维度 | 当前 CAP | ACP v1 |
|---|---|---|
| 协议 | 自定义 `v/id/type/payload` | JSON-RPC 2.0 |
| 传输 | HTTP/WS | stdio 为主，远程自定义传输 |
| 初始化 | 无 | `initialize` + 版本/能力协商 |
| 会话 | `sessionId` 字符串 | `session/new` / `session/load` + `sessionId` |
| 对话 | `chat` / `final` | `session/prompt` / `stopReason` |
| 流式 | `delta/thinking` | `session/update` 的 `agent_message_chunk` / `agent_thought_chunk` |
| 工具 | 客户端注册 + MCP 网关 | Client 通过 `session/new.mcpServers` 提供 MCP |
| 权限 | 客户端工具内部确认（未完整） | `session/request_permission` 框架强制 |
| 取消 | 未实现 | `session/cancel` |
| 文件/终端 | 无 | `fs/*`、`terminal/*`（可选） |

## 5. 目标架构：双栈，不是二选一

```text
外部 ACP Client（编辑器/App/移动端）
        │  ACP JSON-RPC（stdio 或自定义 WS）
        ▼
Gateway 的 ACP Agent Facade
        │  内部统一会话/事件/权限
        ▼
AgentAdaptor（CLI / Codex / Claude / HTTP）
        │
        └── MCP Server ──> 客户端工具（HTTP 回调 / WS）
```

同时保留两条路径：

- **ACP 入口**：给原生 ACP Client 用；
- **CAP 入口**：给现有客户端/旧 SDK 用；
- 两者共享 `AgentAdaptor`、`AgentStreamSink`、`ClientRegistry`。

不建议直接把 CAP 改名 ACP；建议把 CAP 定位为“ACP 的简化 profile / 兼容入口”，等 ACP 入口稳定后再决定是否收敛。

## 6. 分阶段改造

### Phase 0：抽出内部统一模型

把现在绑定在 CAP 上的东西抽出来：

- `PromptContent`：文本/图片/资源块，替代单一 `prompt` 字符串；
- `SessionRef`：ACP sessionId ↔ 内部 sessionId；
- `StreamEvent`：message chunk / thought chunk / tool call / tool update / plan / usage；
- `PermissionRequest`：工具名、参数、风险、选项；
- `StopReason`：end_turn / cancelled / max_tokens 等。

现有 `AgentStreamSink` 可以保留，但需要扩展 `toolCall/toolUpdate/permission/usage`，不能只停留在 `delta/thinking/finish/error`。

### Phase 1：先做 ACP 出口（Gateway 作为 ACP Client）

如果目标是“接入 Codex/Claude 等 ACP Agent”，优先做出口：

- 新增 `AcpAgentAdaptor`，由 Gateway 启动/连接 ACP Agent；
- 实现 `initialize`、`session/new`、`session/prompt`、`session/update`、`session/cancel`；
- 把 ACP 事件翻译成现有 `AgentStreamSink` / CAP 事件；
- 复用 `AgentSessionStore` 保存 ACP sessionId；
- 这样不需要改外部客户端，先获得 ACP Agent 生态。

### Phase 2：再做 ACP 入口（Gateway 作为 ACP Agent）

如果目标是“让小红书 App 这类客户端直接说 ACP”，做入口：

- 新增 `AcpAgentFacade`，实现 ACP Agent 基线方法；
- 传输优先做 **自定义 WebSocket 绑定**（移动端/远程），保持 JSON-RPC 消息格式；stdio 只用于本地子进程场景；
- `initialize` 协商版本/能力；
- `session/new` 创建内部会话，接收 `cwd`、`mcpServers`；
- `session/prompt` 转成 `AgentRequest`，调用现有 `AgentAdaptor`；
- `session/update` 由 `StreamEvent` 映射；
- `session/request_permission` 接入工具风险/确认；
- `session/cancel` 接入取消。

### Phase 3：补齐权限、取消、会话恢复

- `session/request_permission`：Agent 在敏感工具前反向请求 Client；
- 权限强制在 Gateway/执行侧，不依赖 Agent 自觉；
- `session/cancel`：停止 CLI/HTTP Agent，清理 pending tool calls；
- `session/load`：历史会话恢复（可选）；
- `usage_update`、`plan`、`notice` 可选。

### Phase 4：工具面标准化

ACP 的标准做法是：**Client 在 `session/new.mcpServers` 里提供 MCP Server**，Agent 通过 MCP 调工具。

本项目当前是“Gateway 宿主 MCP Server + 客户端注册工具”，这是一个扩展。两种选择：

- **标准路径**：让有能力的客户端暴露 MCP Server，传给 Gateway/Agent；
- **扩展路径**：保留 Gateway MCP，作为 ACP 的自定义能力/`_meta` 扩展，移动端继续走 WS 回调；
- 无论哪种，都要保证工具执行在客户端，Gateway 只做协议转换。

## 7. CAP ↔ ACP 映射建议

| CAP | ACP |
|---|---|
| `register_tools` | `session/new.mcpServers`（标准）或 `_meta` 扩展 |
| `chat` | `session/prompt` |
| `delta` | `session/update` → `agent_message_chunk` |
| `thinking` | `session/update` → `agent_thought_chunk` |
| `tool_call` | `session/update` → `tool_call` + `session/request_permission` |
| `tool_result` | `session/update` → `tool_call_update`（completed） |
| `final` | `session/prompt` 响应 `{ stopReason }` |
| `error` | JSON-RPC error response |
| `cancel`（未实现） | `session/cancel` 通知 |

`callId` 映射到 ACP `toolCallId`；`sessionId` 双向映射；`final.output` 不再单独发事件，而是 `session/prompt` 的结果。

## 8. 关键设计决策

1. **入口/出口分开**：ACP 入口是 Gateway 扮演 Agent；ACP 出口是 Gateway 扮演 Client。两者可以独立落地。
2. **传输**：远程客户端用自定义 WebSocket 绑定，保留 JSON-RPC；stdio 只用于本地；Streamable HTTP 等草案稳定后再评估。
3. **工具面**：标准 ACP 用 `session/new.mcpServers`；本项目现有 Gateway MCP 是扩展，必须明确标注。
4. **权限**：`session/request_permission` 是强制面；工具内部确认是最后一道防线，不能替代。
5. **内容块**：把 prompt 从字符串升级为 text/image/resource 等内容块。
6. **取消**：ACP 的 `session/cancel` 需要真正终止 Agent/工具，而不是只关连接。
7. **兼容**：CAP 和 ACP 双栈运行，避免一次迁移破坏现有客户端。

## 9. 风险

- ACP v1 仍在演进，远程传输/Streamable HTTP 未稳定；
- Claude Code、Codex 等通过适配器接入，能力可能不完整；
- 客户端工具“注册到网关”与 ACP 的“Client 提供 MCP Server”模型不同，需要标准/扩展两条路；
- 权限、会话恢复、取消是跨层能力，不能只加协议字段；
- 双栈会增加维护成本，需要明确哪条是主路径。

## 10. 建议

- 如果当前目标是 **接入 ACP Agent**：先做 Phase 1（Gateway as ACP Client），改动小、收益直接；
- 如果当前目标是 **让客户端说 ACP**：做 Phase 2（Gateway as ACP Agent Facade），先实现基线方法 + WS 自定义传输；
- 不建议直接把 CAP 改名为 ACP；建议保持 CAP 作为兼容入口，ACP 作为正式对齐目标；
- 先做 `initialize/session/new/session/prompt/session/update/session/request_permission/session/cancel` 这条最小闭环，再补 `session/load`、`fs/*`、`terminal/*`。

## 参考

- [ACP Overview](https://agentclientprotocol.com/protocol/v1/overview)
- [Initialization](https://agentclientprotocol.com/protocol/v1/initialization)
- [Prompt Turn](https://agentclientprotocol.com/protocol/v1/prompt-turn)
- [Transports](https://agentclientprotocol.com/protocol/v1/transports)
- [Schema](https://agentclientprotocol.com/protocol/v1/schema)
