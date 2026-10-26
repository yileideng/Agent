# Agent 网关产品与技术方案

> 工作名：**Agent Gateway（Agent 网关）**
> 版本：v0.1（2026-10）
> 目标读者：产品 / 架构 / 客户端接入方 / Agent 接入方
> 文档定位：产品定位、产品形态、核心链路、传输模型、CAP/AAP 协议与演进路线
> 关联文档：[agent-middleware-design.md](./agent-middleware-design.md)（实现层设计与现状评估）、[agent-tool-discovery-and-calling.md](./agent-tool-discovery-and-calling.md)（工具发现与调用机制）、[client-registry-redesign.md](./client-registry-redesign.md)（ClientRegistry 重构记录）

---

## 0. 摘要

**一句话：让客户端用自己的工具，接入可替换的 Agent。**

本项目不是一个"更强的 Agent"，而是客户端与 Agent 之间的**接入与治理层**：

- 客户端把本地工具注册给网关，工具始终在客户端执行；
- 网关负责 Agent 选择、会话归属、工具路由、传输适配、身份与隔离；
- Agent 可替换：Claude Code / Qwen Code / 扣子 / 自研 Brain，客户端无感知。

三个核心判断：

1. **产品形态**：SDK/Connector 为主，控制台为辅；网页表单只是接入向导，不是产品本身。
2. **传输模型**：HTTP callback、WebSocket、SSE 是**并列**的传输绑定，不是主备关系；NAT 后的客户端默认走 WS/SSE。
3. **协议分层**：客户端侧协议（CAP）统一、稳定、版本化；Agent 侧协议（AAP）保持异构，由网关适配。

---

## 1. 产品定位

### 1.1 一句话定位

> **Agent 网关：让客户端用自己的工具，接入可替换的 Agent。**

类比 API 网关：API 网关做认证、路由、限流、协议转换、可观测；Agent 网关做 **Agent 选择、工具路由、会话归属、传输适配、租户隔离**。

### 1.2 要解决的问题：N×M

没有网关时：

```
N 个客户端 × M 个 Agent = N×M 套定制
```

每个客户端要分别对接 Claude Code、Qwen Code、扣子、自研 Brain；每个 Agent 也要分别适配不同客户端的工具协议。接入成本随两端数量相乘。

有网关后：

```
N 个客户端 × 1 套 CAP  +  M 个 Agent × 1 套 AAP  =  N+M
```

客户端只接一套协议，Agent 只接一套适配层；工具留在客户端执行，网关只做目录与路由。

### 1.3 目标客户

| 客户 | 典型场景 | 诉求 |
|---|---|---|
| **客户端开发团队（B 端）** | 小红书、抖音、内容工具、垂直 App | 10 行代码接入、本地工具、可调试、可治理 |
| **业务/运营（B 端）** | 需要配置 Agent、提示词、HTTP 工具 | 不写代码，可视化配置 |
| **个人用户（C 端）** | 个人电脑、个人 App | 在自己的设备上使用 Agent，工具在本地，零公网入口 |

**第一客户是客户端开发团队**，不是终端用户。产品形态与协议设计都优先服务这一类客户。

### 1.4 是什么 / 不是什么

| 是 | 不是 |
|---|---|
| Agent 接入与治理层 | 最强 Agent 本身 |
| 客户端工具网关 | 工具市场（v1 不做） |
| 多端 / 多租户接入 | 应用搭建平台（不是 Coze/Dify） |
| 协议 + SDK + 控制台 | 模型托管 / 模型提供商 |
| 会话归属与路由层 | 通用 RPC 框架 |

### 1.5 北极星指标

- 新客户端从接入到首次工具调用 **< 1 人天**
- 换 Agent 客户端 **零改动**
- 工具调用成功率 / P95 延迟
- 单会话成本
- 跨租户隔离事故 **= 0**

### 1.6 路线：先对内，后对外

| 阶段 | 目标 | 重点 |
|---|---|---|
| **对内** | 一个业务、多端接入，跑通闭环 | 协议、工具路由、WS、成本 |
| **对外** | 开发者自助接入 | SDK、控制台、文档、沙箱、配额 |

对内阶段协议可以激进；对外阶段协议必须稳定、向后兼容。

---

## 2. 产品形态

### 2.1 三位一体

```
┌──────────────── 控制面（Web 控制台）────────────────┐
│ 客户端 / 工具目录 / Agent 配置 / 会话 / 日志 / Playground │
└───────────────────────┬─────────────────────────────┘
                        │ 配置、观测、调试
┌───────────────────────▼─────────────────────────────┐
│              数据面（云端 Agent 网关）                │
│        HTTP callback / WebSocket / SSE / 路由 / 鉴权  │
└───────────────────────┬─────────────────────────────┘
                        │ CAP 协议
┌───────────────────────▼─────────────────────────────┐
│         接入面（客户端 SDK / Connector）              │
│   建连 / 自动注册工具 / 执行工具 / 回调结果 / 重连     │
└─────────────────────────────────────────────────────┘
```

- **数据面**：云端网关，负责协议、路由、鉴权、会话，不直接执行工具。
- **接入面**：SDK 嵌入客户端，或 Connector 作为本地守护进程；工具在代码里声明，连上来自动注册。
- **控制面**：Web 控制台，第一版只做"看"和"调"（连接状态、工具、会话、日志、Playground）。

### 2.2 最小形态：CLI + YAML + Playground

第一版不先做网页表单，而是做一个开发者能在几分钟内跑起来的 CLI：

```bash
agent-gateway connect --token <token> --manifest tools.yaml
```

`tools.yaml` 声明工具、JSON Schema、执行命令：

```yaml
tools:
  - name: get_user_profile
    description: 获取当前登录用户资料
    inputSchema:
      type: object
      properties: {}
    command: ./scripts/get-user-profile.sh

  - name: save_note_draft
    description: 保存笔记草稿
    inputSchema:
      type: object
      properties:
        title: { type: string }
        content: { type: string }
      required: [title, content]
    command: ./scripts/save-note-draft.sh
```

CLI 负责建 WS、发 `hello`、`register_tools`、收到 `tool_call` 执行命令并回传 `tool_result`。

### 2.3 SDK 形态

```ts
const client = new AgentClient({ token, gateway: "wss://gw.example.com" });

client.tool("save_note_draft", {
  description: "保存笔记草稿",
  inputSchema: { /* JSON Schema */ },
  handler: async (args) => noteApi.saveDraft(args),
});

await client.connect();   // 自动 hello + register_tools
```

**最好的注册是不需要注册**：SDK 连上来就自动注册。表单只在无法自动注册时兜底（如 HTTP 工具）。

### 2.4 网页表单的定位

网页表单**不是**产品主形态，它解决的是"登记元数据"，不是"接入并执行工具"：

| 表单能做 | 表单不能做 |
|---|---|
| 登记客户端类型、名称、认证方式 | 执行本地工具 |
| 登记工具名称、描述、JSON Schema | 维持 WS/SSE 连接 |
| 登记 HTTP 工具的 callback URL | 替代客户端代码 |
| 生成 SDK 配置 / 代码片段 | 表达复杂的运行时状态 |

正确做法：**向导 → 可运行的代码/配置**。

1. 创建客户端（名称、类型、认证方式）
2. 添加工具（手填 JSON Schema / 导入 OpenAPI）
3. 选择传输（HTTP callback / WS / SSE）
4. **生成 SDK 初始化代码或 YAML 配置**
5. Playground 发一条消息，看到工具被调用

对 **HTTP 工具**，表单可以直接生效（真正的 no-code）；对**本地工具**，表单只生成配置，执行仍在客户端。

### 2.5 两条产品线，v1 不要混做

| 产品线 | 客户 | 形态 | 关键能力 |
|---|---|---|---|
| **开发者网关** | 客户端开发团队 | SDK + 控制台 + 网关 | 本地工具、调试、治理 |
| **个人 Connector** | 个人用户 | 桌面/移动 Connector + 聊天 UI | 本地工具、零公网入口、隐私 |

同一个后端可以服务两条线，但产品界面、分发方式、支持成本完全不同。v1 选一条。

---

## 3. 核心链路

### 3.1 产品链路（用户视角）

以小红书笔记创作为例：

1. 用户在客户端输入"帮我写一篇咖啡测评"
2. Agent 调客户端工具 `get_user_profile`，拿用户画像/偏好
3. Agent 调客户端工具 `search_hot_topics`，拿热点/素材
4. Agent 流式产出 3 个标题 + 正文
5. 用户确认/修改
6. Agent 调客户端工具 `save_note_draft`，保存草稿（或发布）
7. 客户端展示结果

这条链路证明三件事：

- Agent 能用客户端私有数据；
- Agent 能在客户端执行写操作；
- 用户能感知到流式产出。

### 3.2 技术链路（协议视角）

```
Client                    Gateway                     Agent
  |-- hello(token) -------->|                            |
  |<-- hello_ack -----------|                            |
  |-- register_tools ------>|                            |
  |<-- tools_ack -----------|                            |
  |-- chat(session=null) -->|-- AgentRequest ----------->|
  |<-- chat_ack(session) ---|                            |
  |                         |<-- tool_call --------------|
  |<-- tool_call(callId) ---|                            |
  |-- tool_result(callId) ->|-- ToolResult ------------->|
  |                         |<-- delta/final ------------|
  |<-- delta/final ---------|                            |
```

### 3.3 MVP 范围（M1）

| 项 | 范围 |
|---|---|
| 客户端 | 1 个（PC 模拟器或 App） |
| Agent | `self`（确定性，做协议一致性）+ `cli`（真实效果） |
| 工具 | `get_user_profile`（读）、`search_hot_topics`（读）、`save_note_draft`（写） |
| 传输 | WS + HTTP callback（SSE 紧随） |
| 协议 | CAP v0.1：hello / register_tools / chat / tool_call / tool_result / final / error |
| 会话 | 内存存储，支持 `sessionId` 多轮 |
| 身份 | 简单 Bearer token（不做完整 OAuth） |

### 3.4 可弱化 / 不可弱化

| 可弱化（后补） | 不可弱化（协议级） |
|---|---|
| 异步任务状态机 | `token` 身份与连接绑定 |
| 幂等键 / 去重 | `callId` 关联 |
| 自动重试 | 超时 |
| 结果持久化 | 心跳 / 重连 |
| 租户配额 / 计费 | 错误码 |
| CLI 沙箱 | 协议版本号 |
| 审计落库 | 身份服务端注入 |

> 判断标准：**会改变协议消息语义的，不能后补；只影响可靠性和运维的，可以后补。**

---

## 4. 传输模型

### 4.1 三种传输并列

| 传输 | 适用 | 优点 | 代价 |
|---|---|---|---|
| **HTTP callback** | 服务端客户端、同 VPC、有公网入口 | 无长连接、易水平扩展、最简单 | 客户端必须可寻址 |
| **WebSocket** | PC App、移动 App、浏览器、NAT 后 | 双向、低延迟、多路复用 | 需心跳/重连；部分代理不友好 |
| **SSE + POST** | 同上，尤其 WS 被代理拦截时 | 纯 HTTP、穿透好、自动重连、易鉴权 | 下行单向，上行要另发 POST |
| **长轮询** | 极端受限环境 | 兼容性最好 | 延迟高、连接开销大 |

关键认知：

- **HTTP callback 的前提是客户端可被寻址**。个人 PC、手机 App、浏览器在 NAT 后面，网关根本连不进去，这不是"回调慢"，是"回调不可能"。
- **WS/SSE 的本质是客户端主动出站建连**，穿透 NAT，不需要公网入口。
- 三者是**并列的传输绑定**，不是"HTTP 为主、WS 为辅"。

### 4.2 选择矩阵

| 客户端 | 默认传输 | 备选 |
|---|---|---|
| 服务端（自有后端、同 VPC） | HTTP callback | WS |
| PC App / 桌面端 | WebSocket | SSE |
| 移动 App | WebSocket | SSE |
| 浏览器（Web） | WebSocket | SSE |
| 代理限制严格的企业网络 | SSE + POST | 长轮询 |

传输由客户端声明、网关按连接选择，不由 `ClientType` 枚举硬编码。

### 4.3 协议与传输解耦

业务代码只认 CAP 信封，不认 WS/SSE/HTTP；传输层只负责把信封搬来搬去。

```
CAP 消息（与传输无关）
   ├── HTTP 绑定：POST /v1/chat，回调 POST {base}{path}
   ├── WS 绑定：wss://.../v1/ws
   └── SSE 绑定：GET /v1/events + POST /v1/messages
```

### 4.4 NAT 与"零公网入口"

对个人 PC / 个人 App，产品级卖点是：

> **只要一条出站连接 + 一个 token，不需要公网 IP、不需要内网穿透、不需要开端口。**

这也是 WS/SSE 必须和 HTTP callback 并列提供的原因。

---

## 5. 协议分层

### 5.1 分层

```
Client  <--- CAP（统一、稳定、版本化） --->  Gateway  <--- AAP（异构、适配器） --->  Agent
```

| 层 | 全称 | 面向 | 设计原则 |
|---|---|---|---|
| **CAP** | Client Access Protocol | 客户端 | 统一、稳定、版本化、有 SDK |
| **AAP** | Agent Adaptation Protocol | Agent | 异构、适配、能力协商，不强行统一 |
| **Canonical Model** | 内部通用模型 | 网关内部 | Session / ToolDefinition / ToolCall / ToolResult / AgentEvent |

### 5.2 为什么不对称

- 客户端是你自己定义的，可以要求它遵守一套稳定协议。
- Agent 是外部的：Claude Code 是 CLI + MCP，扣子是厂商 API，自研 Brain 是 HTTP。强行统一只会**削平能力**（子 agent、hooks、skills、planning 等强项丢失）。
- 所以：**CAP 统一，AAP 异构；网关吸收差异，对外暴露统一的 `AgentEvent`。**

### 5.3 MCP 的位置

**MCP 是 Agent 侧的工具协议，不是客户端侧协议。**

- 网关对 CLI Agent 暴露 MCP Server（`tools/list` / `tools/call`）；
- 网关对客户端说 CAP；
- 网关负责把 CAP `tool_result` 翻译成 MCP `content`，把 MCP `tools/call` 翻译成 CAP `tool_call`。

不要把 MCP 一路推到客户端：客户端在 NAT 后、不是 Agent，而且会被 MCP 版本绑死；同时会绕过网关的鉴权、工具治理、租户隔离和审计。

---

## 6. CAP v0.1（客户端接入协议）

### 6.1 信封

所有传输共用一个 JSON 信封：

```json
{
  "v": "0.1",
  "id": "msg-7f3c...",
  "type": "hello | register_tools | chat | delta | thinking | tool_call | tool_result | final | cancel | ping | pong | error",
  "ts": 1730000000000,
  "sessionId": "sess-...",
  "payload": {}
}
```

字段说明：

| 字段 | 必填 | 说明 |
|---|---|---|
| `v` | 是 | 协议版本，`0.1` |
| `id` | 是 | 消息 ID，用于关联响应 |
| `type` | 是 | 消息类型 |
| `ts` | 是 | 毫秒时间戳 |
| `sessionId` | 否 | 会话 ID；`hello` / `register_tools` 时可空 |
| `payload` | 是 | 类型相关负载 |

### 6.2 消息类型

| type | 方向 | 作用 | 关键字段 |
|---|---|---|---|
| `hello` | C→S | 认证 + 绑定连接 | `token`, `clientId`, `endUserId`, `deviceId`, `capabilities` |
| `hello_ack` | S→C | 版本协商 + 连接确认 | `serverVersion`, `connectionId`, `transports` |
| `register_tools` | C→S | 上报工具目录 | `tools[]`, `replace` |
| `tools_ack` | S→C | 注册结果 | `registered`, `catalogVersion` |
| `chat` | C→S | 发起一轮对话 | `sessionId?`, `input`, `agent?`, `context?` |
| `chat_ack` | S→C | 已受理 | `chatId`, `sessionId` |
| `delta` | S→C | 流式文本 | `chatId`, `seq`, `text` |
| `thinking` | S→C | 思考过程增量 | `chatId`, `seq`, `text` |
| `tool_call` | S→C | 调客户端工具 | `callId`, `tool`, `arguments`, `deadlineMs` |
| `tool_result` | C→S | 回传结果 | `callId`, `status`, `result`, `error` |
| `final` | S→C | 本轮结束 | `chatId`, `output`, `usage` |
| `error` | S→C | 错误 | `code`, `message`, `retryable`, `chatId?` |
| `cancel` | C→S | 取消对话/工具调用 | `targetId` |
| `ping` / `pong` | 双向 | 心跳 | — |

> `hello` 中的 `clientId` / `endUserId` 仅用于一致性校验与日志，**权威值一律来自 token**；两者不一致时以 token 为准并记录告警。

### 6.3 ToolDefinition

工具定义使用 **JSON Schema**，不再使用 `Map<String,String>`：

```json
{
  "name": "save_note_draft",
  "title": "保存笔记草稿",
  "description": "把标题、正文、话题标签保存为草稿",
  "inputSchema": {
    "type": "object",
    "properties": {
      "title": { "type": "string", "description": "笔记标题" },
      "content": { "type": "string", "description": "笔记正文" },
      "topics": {
        "type": "array",
        "items": { "type": "string" },
        "description": "话题标签"
      }
    },
    "required": ["title", "content"]
  },
  "outputSchema": {
    "type": "object",
    "properties": {
      "draftId": { "type": "string" }
    }
  },
  "riskLevel": "write",
  "timeoutMs": 30000,
  "idempotent": false,
  "version": "1.0"
}
```

字段说明：

| 字段 | 必填 | 说明 |
|---|---|---|
| `name` | 是 | 客户端内唯一，建议 `snake_case` |
| `title` | 否 | 人类可读名称 |
| `description` | 是 | 给模型看的用途说明 |
| `inputSchema` | 是 | JSON Schema，描述参数 |
| `outputSchema` | 否 | 结果结构，便于客户端/网关校验 |
| `riskLevel` | 否 | `read` / `write` / `high`，用于授权与确认 |
| `timeoutMs` | 否 | 单次调用超时，缺省取客户端配置 |
| `idempotent` | 否 | 是否可安全重试，默认 `false` |
| `version` | 否 | 工具定义版本 |

### 6.4 典型时序

```
1. C → S: hello { token, clientId, endUserId, deviceId, capabilities }
2. S → C: hello_ack { serverVersion, connectionId, transports }
3. C → S: register_tools { tools: [...] }
4. S → C: tools_ack { registered: 3 }
5. C → S: chat { sessionId: null, input: "帮我写一篇咖啡测评" }
6. S → C: chat_ack { chatId, sessionId }
7. S → C: delta { chatId, text: "先了解一下你的偏好" }
8. S → C: tool_call { callId, tool: "get_user_profile", arguments: {}, deadlineMs: 30000 }
9. C → S: tool_result { callId, status: "success", result: { ... } }
10. S → C: delta { chatId, text: "..." }
11. S → C: final { chatId, output: "..." }
```

### 6.5 传输绑定

#### HTTP callback（服务端客户端）

- 客户端注册时提供 `baseCallbackUrl` + 可选 `defaultCallbackPath`；
- 工具可提供自己的 `callbackPath`，完整地址 = `baseCallbackUrl + (tool.callbackPath ?? defaultCallbackPath)`；
- `chat`：`POST /v1/chat`，返回 JSON 或 SSE 流；
- `tool_call`：网关 `POST {callbackUrl}`，请求体为 CAP 信封，客户端在 HTTP 响应体中返回 `tool_result`。

#### WebSocket（NAT 客户端）

- 连接：`wss://{gateway}/v1/ws?token={token}`；
- 所有 CAP 消息通过 WS 文本帧双向传输；
- 30s `ping`/`pong`；断线指数退避重连；
- 重连后重新 `hello`，`register_tools` 可增量或全量。

#### SSE + POST（NAT 客户端，WS 受限）

- 下行：`GET /v1/events?token={token}`，`text/event-stream`；
- 上行：`POST /v1/messages`，请求体为 CAP 信封；
- SSE 事件 `id` 用于 `Last-Event-ID` 续传；
- 工具结果通过 `POST /v1/messages` 回传。

### 6.6 身份、会话、路由

三条硬规则：

1. **身份只从 token 来**：`token → tenantId + clientId + endUserId + deviceId + scopes`。请求体里的 `clientId` / `endUserId` 一律不信任。
2. **会话绑连接**：`sessionId → connectionId`；工具调用按 session 找连接，找不到返回 `CLIENT_OFFLINE`，不允许"按 clientId 随便找一个"。
3. **身份服务端注入**：工具参数里不允许模型传 `userId` / `tenantId`；这些由网关在执行前注入，防止混淆代理。

路由优先级：

```
sessionId 精确匹配 → (clientId, endUserId, deviceId) 唯一连接 → CLIENT_OFFLINE
```

### 6.7 错误码

| code | 含义 | 可重试 |
|---|---|---|
| `AUTH_FAILED` | token 无效/过期 | 否 |
| `CLIENT_OFFLINE` | 客户端不在线 | 是（重连后） |
| `TOOL_NOT_FOUND` | 工具未注册/已注销 | 否 |
| `TOOL_TIMEOUT` | 工具执行超时 | 视工具 `idempotent` |
| `TOOL_FAILED` | 工具执行失败 | 视错误类型 |
| `AGENT_ERROR` | Agent 调用失败 | 是 |
| `RATE_LIMITED` | 触发限流/配额 | 是（退避） |
| `INVALID_REQUEST` | 协议/参数错误 | 否 |

### 6.8 版本、心跳、重连

- `v` 每个信封都带；`hello` 协商，服务端返回 `serverVersion` 与支持的版本范围。
- minor 版本只做加法；删除/改语义必须升 major。
- WS：30s `ping`/`pong`；连续 2 次无 `pong` 判定断线；指数退避重连（1s、2s、4s…上限 30s）。
- SSE：`Last-Event-ID` 续传；重连后重新 `hello`。
- `callId` 必须回传；`idempotent=true` 的工具才允许自动重试。

### 6.9 示例：工具调用

```json
{
  "v": "0.1",
  "id": "msg-9a1",
  "type": "tool_call",
  "ts": 1730000001234,
  "sessionId": "sess-001",
  "payload": {
    "callId": "call-77",
    "tool": "save_note_draft",
    "arguments": {
      "title": "上海咖啡店测评｜这 5 家值得反复去",
      "content": "...",
      "topics": ["咖啡", "上海探店"]
    },
    "deadlineMs": 30000
  }
}
```

```json
{
  "v": "0.1",
  "id": "msg-9a2",
  "type": "tool_result",
  "ts": 1730000001456,
  "sessionId": "sess-001",
  "payload": {
    "callId": "call-77",
    "status": "success",
    "result": { "draftId": "draft-123" }
  }
}
```

---

## 7. AAP v0.1（Agent 适配协议）

### 7.1 Canonical AgentEvent

网关内部统一使用 `AgentEvent`，所有适配器都映射到它：

```json
{
  "type": "delta | thinking | tool_call | final | error | usage",
  "chatId": "chat-1",
  "text": "…",
  "toolName": "save_note_draft",
  "arguments": {},
  "output": "…",
  "usage": { "inputTokens": 0, "outputTokens": 0 },
  "error": { "code": "AGENT_ERROR", "message": "…" }
}
```

| type | 含义 |
|---|---|
| `delta` | 文本增量 |
| `thinking` | 思考过程增量，独立通道 |
| `tool_call` | Agent 请求调用工具 |
| `final` | 本轮最终输出 |
| `error` | Agent 错误 |
| `usage` | token/成本统计（可选） |

### 7.2 CLI 适配器（Claude Code / Qwen Code）

- **对话**：`{command} -p <prompt> --output-format stream-json`，逐行解析 JSON 事件。
- **流式**：`assistant` 文本映射为 CAP `delta`；`thinking` / `thinking_delta` / `reasoning_content` 映射为 CAP `thinking`，两者不混流。
- **工具**：网关对 CLI 暴露 MCP Server（`POST /mcp/{sessionToken}`），CLI 通过 `--mcp-config` 连接。
- **会话**：`--resume <agentSessionRef>`；但**会话真相源在网关**，CLI 的 session 只是加速。
- **系统提示**：`--append-system-prompt` 注入客户端提示词 + 语言指令 + 安全约束。
- **能力**：子 agent、hooks、skills 等 CLI 私有能力不强行映射到 CAP；需要时作为 Agent 侧增强，不影响客户端协议。

### 7.3 自研 Brain 适配器（HTTP）

- 接口：`POST /v1/brain/chat`
- 请求：`{ clientId, sessionId, newSession, userPrompt, clientPrompt, tools? }`
- 响应：`{ type: "CLIENT_TOOL_CALL" | "FINAL_OUTPUT", toolName?, toolParameters?, output? }`
- Brain 保持**无状态**；会话由网关 `AgentSessionStore` 持有。
- 网关驱动 Agent Loop：`CLIENT_TOOL_CALL → 路由客户端 → tool_result → 再调 Brain → FINAL_OUTPUT`，受 `max-steps` 限制。
- **本期不支持流式**：只调用一次性 HTTP 接口，产生一个 `final`，不产生 `delta` / `thinking`；不做切块模拟。

### 7.4 扣子适配器（Coze）

- 使用 Coze OpenAPI 的流式对话；
- `CONVERSATION_CHAT_REQUIRES_ACTION` 事件映射为 `tool_call`；
- 会话映射：网关 `sessionId ↔ conversationId`；
- 适配器负责厂商事件 → `AgentEvent` 的翻译。

### 7.5 会话归属与能力协商

| 约束 | 做法 |
|---|---|
| 会话可迁移 | 会话归属网关；当前只保留 sessionId，消息历史待补；Agent 侧 session ref 仅作加速 |
| 能力不削平 | `AgentAdaptor.capabilities()` 声明能力；网关按能力路由，不假设所有 Agent 等价 |
| 输出统一 | 所有适配器输出 `AgentEvent` |
| 降级 | Agent 故障时按配置切换备用 Agent |

---

## 8. 安全与隔离（最小集）

### 8.1 身份

- 客户端用 `clientId + secret` 换短期 token（或平台签发的 API Key）。
- token 内含 `tenantId` / `clientId` / `endUserId` / `deviceId` / `scopes` / `exp`。
- 网关从 token 解析身份，**不信任请求体**。

### 8.2 工具授权

- 每个工具带 `riskLevel`：`read` / `write` / `high`。
- `write` 工具可配置"执行前确认"；`high` 工具默认需要用户确认。
- 工具调用前校验：该工具属于当前 `tenantId` 与 `clientId`，且当前 token 的 `scopes` 允许。
- **身份参数由网关注入**，模型不能指定 `userId` / `tenantId`。

### 8.3 回调与 SSRF

- `callbackUrl` 必须命中租户备案的域名白名单；
- 禁止私网 IP、localhost、元数据地址；
- 回调请求带签名（HMAC）或短期 token；
- 注册后可要求客户端完成一次 challenge，确认地址可控。

### 8.4 租户隔离边界

| 维度 | 隔离键 |
|---|---|
| 工具目录 | `(tenantId, clientId, toolName)` |
| 会话 | `(tenantId, clientId, endUserId, sessionId)` |
| 连接 | `connectionId → (tenantId, clientId, endUserId, deviceId)` |
| 数据 | `tenant_id` 列 + 存储前缀 |
| 运行时 | 每会话独立工作目录；CLI 沙箱（后续） |
| 配额 | 每租户 QPS / 并发 / token 预算（后续） |

### 8.5 本阶段暂缓

- 完整 OAuth / SSO；
- 配额与计费；
- CLI 容器沙箱；
- 审计落库与合规；
- 数据加密与密钥管理（KMS）。

> 注意：**身份、工具授权、回调白名单不能暂缓**；它们是协议的一部分，后补会改协议。

---

## 9. 与现有代码的映射

### 9.1 现状

| 模块 | 端口 | 职责 | 关键类 |
|---|---|---|---|
| `AgentAdaptor` | 8080 | 网关：适配器、会话、工具目录、MCP、WS | [AgentAdaptor.java](../AgentAdaptor/src/main/java/com/agent/demo/clientagent/agent_adaptor/AgentAdaptor.java)、[ClientRegistry.java](../AgentAdaptor/src/main/java/com/agent/demo/clientagent/client_registry/ClientRegistry.java)、[WsSessionManager.java](../AgentAdaptor/src/main/java/com/agent/demo/clientagent/ws/WsSessionManager.java) |
| `BrainAgent` | 8081 | 自研 Agent 服务：无状态决策 | [BrainController.java](../BrainAgent/src/main/java/com/agent/demo/agentbrain/controller/BrainController.java)、[SelfAgentBrain.java](../BrainAgent/src/main/java/com/agent/demo/agentbrain/service/SelfAgentBrain.java) |
| `AgentClientSdk` | — | 客户端 SDK：统一 HTTP 回调与 WebSocket 接入，被 Client 依赖 | [AgentClient.java](../AgentClientSdk/src/main/java/com/agent/demo/agentsdk/AgentClient.java)、[Transport.java](../AgentClientSdk/src/main/java/com/agent/demo/agentsdk/Transport.java) |
| `Client` | 8082 | SDK 的示例消费者：注册工具、执行回调、WS 移动端 | [Client.java](../Client/src/main/java/com/agent/demo/agentclient/client/Client.java)、[MobileClient.java](../Client/src/main/java/com/agent/demo/agentclient/client/MobileClient.java) |

### 9.2 差距

| 能力 | 现状 | 目标 | 差距 |
|---|---|---|---|
| CAP 统一信封 | 现有 REST/WS 各自定义 | 一套 `v/id/type/payload` | 需抽象消息层 |
| 工具定义 | JSON Schema（`inputSchema`） | JSON Schema | 已统一，`parameters` 已移除 |
| 身份 | `clientId` 自报 | token 解析 | 需认证层 |
| 传输 | HTTP + WS | HTTP + WS + SSE | 需抽 `ClientTransport` |
| Agent 协议 | 各自适配 | 统一 `AgentEvent` | 需事件模型 |
| 产品形态 | 三个 Spring Boot 服务 + 客户端 SDK | CLI/SDK + 控制台 | SDK 已有（HTTP/WS）；CLI/控制台仍缺 |
| 会话 | 内存 | 可持久化 | 后续 |
| 安全 | 基本裸奔 | token + 白名单 + 授权 | P0 必补 |

### 9.3 复用与改造

**直接复用**：

- `AgentAdaptor` 接口与 `impl` 三实现（self / cli / coze）；
- `AgentSessionStore`（会话归属）；
- `ClientRegistry`（客户端与工具目录）；
- `WsSessionManager`（连接与 callId 匹配）；
- `BrainAgent` 的 HTTP 契约。

**需要改造**：

1. `ToolDefinition` 改为 JSON Schema；
2. `ClientType` 从 `SERVER/MOBILE` 二值枚举，改为 `transports[]` 能力声明 + 默认传输；
3. 新增 `ClientTransport` 抽象：`HttpCallbackTransport` / `WebSocketTransport` / `SseTransport`；
4. 新增 CAP 消息层：统一信封、消息路由、错误码；
5. 新增 token 认证与身份注入；
6. 新增 `AgentEvent` 统一模型（当前是 `AgentResponse`）；
7. 新增 CLI + YAML + Playground 的最小产品形态。

---

## 10. 演进路线

| 阶段 | 目标 | 交付 |
|---|---|---|
| **P0（1–2 周）** | 证明协议与核心链路 | CLI + YAML + WS + Playground；self brain；3 个工具 |
| **P1** | 打通双传输与 SDK | CAP v0.1；WS + HTTP callback；TS/Java SDK；最小控制台 |
| **P2** | 身份与治理 | token；JSON Schema；工具授权；注册向导；OpenAPI 导入 |
| **P3** | 多端与流式 | SSE；重连；`/mobile/chat`；Connector 雏形 |
| **P4** | 企业化 | 私有化；SSO；配额；审计；CLI 沙箱 |

> 每一阶段都以"一个真实场景 + 一条真实客户端 + 一个真实 Agent"验收，不做纯架构交付。

---

## 11. 待决策与风险

### 11.1 待决策

1. **定位先对内还是直接对外？** 建议先对内（一个业务、多端），再对外。
2. **第一个垂直场景是否就是小红书笔记创作？** 建议是，MVP 工具按 `get_user_profile` / `search_hot_topics` / `save_note_draft` 定。
3. **传输优先级**：WS 先做，还是 SSE 先做？建议 WS 先（双向、状态机简单），SSE 复用同一信封。
4. **CLI 的定位**：实验/内部适配器，还是生产主链路？建议前者；生产优先 API 型 Agent。
5. **B 端 SDK 与 C 端 Connector 是否分两条产品线？** 建议 v1 只做 B 端。

### 11.2 风险

| 风险 | 影响 | 对策 |
|---|---|---|
| 协议设计过度 | 接入成本高 | v0.1 只保留 7 种消息，加法演进 |
| 适配器削平 Agent 能力 | 复用价值下降 | 能力协商 + 允许 Agent 私有增强 |
| CLI 生产化成本 | 运维/合规 | 先实验定位；生产用 API 型 Agent |
| 安全后补 | 改协议、返工 | 身份/授权/白名单 P0 就做 |
| 平台先行 | 没有真实场景 | 先锁定一个垂直场景 + eval |
| 表单做出"假接入" | 用户填完卡住 | 表单必须产出可运行代码/配置 |

---

## 12. 附录

### 12.1 术语

| 术语 | 含义 |
|---|---|
| CAP | Client Access Protocol，客户端接入协议 |
| AAP | Agent Adaptation Protocol，Agent 适配协议 |
| AgentEvent | 网关内部统一 Agent 事件模型 |
| Canonical Model | 网关内部通用模型 |
| NAT 穿透 | 客户端主动出站建连，绕过 NAT |
| 零公网入口 | 客户端不需要公网 IP/端口，只需出站连接 |

### 12.2 错误码

见 6.7。

### 12.3 相关文档

- [client-integration.md](./client-integration.md)：客户端接入指南（HTTP 回调 / WebSocket、Java / JS 示例、排障清单）
- [streaming-design.md](./streaming-design.md)：Agent 文本流式输出（CAP SSE）设计
- [agent-middleware-design.md](./agent-middleware-design.md)：实现层设计与当前完成度评估
- [agent-tool-discovery-and-calling.md](./agent-tool-discovery-and-calling.md)：Agent 如何发现并调用客户端工具
- [client-registry-redesign.md](./client-registry-redesign.md)：ClientRegistry 重构方案

### 12.4 后续文档计划

- `docs/protocol/cap.md`：CAP 完整消息与 JSON Schema 定义
- `docs/protocol/aap.md`：AAP 适配器契约与 AgentEvent
- `docs/protocol/transports.md`：HTTP / WS / SSE 绑定细节
- `docs/product/cli-quickstart.md`：CLI + YAML 接入指南
