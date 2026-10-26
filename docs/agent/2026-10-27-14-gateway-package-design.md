# AgentGateway 包与类划分设计

> 目标：解决当前包划分按“技术名词”堆叠的问题，改成按“变化原因/职责”划分
> 范围：`AgentGateway` 模块；`AgentClientSdk`、`BrainAgent`、`Client` 保持现有包结构
> 状态：设计稿，未执行重构

## 1. 当前问题

### 1.1 根包和模块名不一致

- 模块叫 `AgentGateway`；
- 根包还是 `com.agent.demo.clientagent`。

`clientagent` 是早期“客户端 Agent”的命名，现在模块职责已经是网关，根包应改为 `com.agent.demo.gateway`。

### 1.2 `controller` 混了四类入口

```text
controller/
├── ChatController          CAP 对话入口
├── ClientToolController    客户端注册/工具管理
├── AgentController         Agent 能力发现
└── McpServerController     MCP Server 入口
```

它们生命周期、协议、调用方都不同，放在一个包只会让“新增一个入口”变成继续往 `controller` 里塞类。

### 1.3 `acp_agent` 混了接口、注册表、协议实现和适配器

```text
acp_agent/
├── AcpAgent                接口
├── AcpAgentRegistry        注册表
├── acp/                    ACP 协议实现
└── impl/                   CLI / Self / Coze 适配器
```

`AcpAgent` 是网关的端口，`acp/` 是协议适配器，`impl/` 是其他 Agent 适配器；三者不是同一层，应该拆开。

### 1.4 `core` 是杂物间

```text
core/
├── AgentRequest / AgentResponse   领域 DTO
├── AgentStreamSink                端口
├── AgentSessionStore              会话存储
└── ToolCallContext                客户端工具上下文
```

“core”没有说明任何职责，任何不知道放哪的类最后都会放进来。

### 1.5 `client_registry` 混了元数据和执行

`ClientRegistry` 既管理客户端/工具目录，又负责 HTTP/WS 工具调用。注册表和调用器是两个变化原因，应拆开。

## 2. 设计原则

1. **按变化原因分包**：改 CAP 协议、改 MCP 协议、改某个 Agent 适配器，不应该改同一个包。
2. **端口与适配器分离**：`AcpAgent` 是端口，`acp/`、`cli/`、`self/`、`coze/` 是适配器。
3. **入口薄，服务厚**：Controller 只做协议解析/响应，编排放 Service。
4. **DTO 不跨层乱放**：CAP/MCP/WS 的线上 DTO 放各自 `api` 子包；内部模型放领域包。
5. **依赖方向单一**：`api -> application -> domain`，`adapter` 实现 `domain` 端口；不要反向依赖。
6. **不为了分层而分层**：项目规模不大，不引入 `domain/entity/repository` 全套 DDD。

## 3. 目标包结构

```text
com.agent.demo.gateway
├── GatewayApplication.java
│
├── api
│   ├── cap
│   │   ├── ChatController.java
│   │   ├── ClientToolController.java
│   │   └── dto
│   │       ├── CapEnvelope.java
│   │       ├── ChatPayload.java
│   │       ├── RegisterToolsPayload.java
│   │       └── CapResponse.java
│   ├── agent
│   │   └── AgentController.java
│   ├── mcp
│   │   ├── McpServerController.java
│   │   └── dto
│   │       ├── McpRequest.java
│   │       └── McpError.java
│   └── ws
│       ├── AgentWebSocketHandler.java
│       ├── WebSocketConfig.java
│       └── dto
│           ├── WsEnvelope.java
│           └── WsPayload.java
│
├── chat
│   ├── ChatService.java
│   ├── ChatCommand.java
│   └── ChatResult.java
│
├── agent
│   ├── AcpAgent.java
│   ├── AcpAgentRegistry.java
│   ├── AgentRequest.java
│   ├── AgentResponse.java
│   ├── AgentStreamSink.java
│   ├── session
│   │   └── AgentSessionStore.java
│   ├── acp
│   │   ├── AcpClientAgent.java
│   │   ├── AcpConnection.java
│   │   ├── AcpSession.java
│   │   ├── AcpAgentProfile.java
│   │   └── AcpException.java
│   └── adapters
│       ├── cli
│       │   └── CliAgentAdaptor.java
│       ├── self
│       │   └── SelfAgentAdaptor.java
│       └── coze
│           └── CozeAgentAdaptor.java
│
├── client
│   ├── ClientRegistry.java
│   ├── ClientRegistration.java
│   ├── ClientType.java
│   ├── ToolDefinition.java
│   ├── ToolCallContext.java
│   ├── ClientToolService.java
│   └── transport
│       ├── ToolInvoker.java
│       ├── HttpToolInvoker.java
│       └── WsToolInvoker.java
│
├── mcp
│   └── McpToolService.java
│
└── common
    ├── error
    │   └── GatewayException.java
    └── json
        └── JsonSupport.java
```

## 4. 包职责

| 包 | 职责 | 不应该做什么 |
|---|---|---|
| `api.cap` | CAP 请求解析、响应封装、HTTP/SSE 入口 | 不直接调 Adaptor，不写业务编排 |
| `api.agent` | Agent 能力发现 | 不持有 Agent 进程 |
| `api.mcp` | MCP JSON-RPC 入口 | 不直接查 ClientRegistry 细节 |
| `api.ws` | 客户端 WebSocket 接入 | 不决定工具用 HTTP 还是 WS |
| `chat` | 一轮对话编排：选 Agent、建请求、流式转发 | 不解析 CAP 信封 |
| `agent` | Agent 端口、请求/响应/事件模型、注册表 | 不关心客户端 HTTP/WS |
| `agent.acp` | ACP 协议连接、会话、事件映射 | 不感知 CAP |
| `agent.adapters.*` | 各 Agent 的协议适配 | 不互相依赖 |
| `client` | 客户端注册、工具目录、工具调用编排 | 不解析 CAP 信封 |
| `client.transport` | HTTP/WS 具体投递方式 | 不管理客户端目录 |
| `mcp` | MCP 工具发现/调用服务 | 不处理 HTTP 状态码 |
| `common` | 极少量通用工具 | 不塞业务类 |

## 5. 关键类拆分

### 5.1 ChatController -> ChatController + ChatService

`ChatController` 目前有 328 行，混合了：

- CAP 信封解析；
- Agent 选择；
- 会话 ID 处理；
- SSE 生命周期；
- 同步/流式两种响应。

拆成：

```text
api.cap.ChatController
  - 解析 CapEnvelope
  - 调 ChatService.chat / ChatService.stream
  - 返回 JSON 或 SseEmitter

chat.ChatService
  - 选 Agent
  - 构造 AgentRequest
  - 调 AcpAgent.chatStream
  - 处理 session/chatId

chat.ChatCommand / ChatResult
  - 内部模型，不直接暴露给 HTTP
```

### 5.2 ClientToolController -> api.cap + client

`ClientToolController` 负责注册、查询、注销客户端；具体工具执行不应由它做。

```text
api.cap.ClientToolController
  - 解析注册/查询/注销请求
  - 调 ClientToolService

client.ClientToolService
  - 注册客户端
  - 管理工具目录
  - 选择 HttpToolInvoker / WsToolInvoker
```

### 5.3 ClientRegistry -> ClientRegistry + ToolInvoker

`ClientRegistry` 只保留：

- `clientId -> ClientRegistration`；
- 工具查询；
- 回调地址解析。

新增：

```text
client.transport.ToolInvoker
  - String invoke(ToolCallContext, String toolName, Map<String,Object> args)

client.transport.HttpToolInvoker
  - SERVER 客户端，HTTP 回调

client.transport.WsToolInvoker
  - MOBILE 客户端，WebSocket 推送
```

这样 `ClientRegistry` 不再依赖 `RestClient` 和 `WsSessionManager`，测试也更容易。

### 5.4 McpServerController -> api.mcp + mcp

`McpServerController` 只做 JSON-RPC 解析/响应；`McpToolService` 负责：

- `tools/list` 从 `ClientRegistry` 取工具；
- `tools/call` 调 `ClientToolService`；
- MCP content/isError 包装。

### 5.5 acp_agent -> agent + agent.acp + agent.adapters

```text
agent.AcpAgent                 端口
agent.AcpAgentRegistry         注册表
agent.acp.AcpClientAgent       ACP 适配器
agent.acp.AcpConnection        传输
agent.acp.AcpSession           会话
agent.adapters.cli.CliAgentAdaptor
agent.adapters.self.SelfAgentAdaptor
agent.adapters.coze.CozeAgentAdaptor
```

`AcpAgent` 不放在 `acp` 子包里，因为它是所有 Agent 的端口，不是 ACP 专属。

### 5.6 core 拆掉

| 原类 | 新位置 |
|---|---|
| `AgentRequest` | `agent.AgentRequest` |
| `AgentResponse` | `agent.AgentResponse` |
| `AgentStreamSink` | `agent.AgentStreamSink` |
| `AgentSessionStore` | `agent.session.AgentSessionStore` |
| `ToolCallContext` | `client.ToolCallContext` |

## 6. 依赖规则

```text
api.*        -> chat / agent / client / mcp
chat         -> agent
mcp          -> client
client       -> client.transport
agent.acp    -> agent
agent.adapters.* -> agent
common       -> 无业务依赖
```

禁止：

- `agent` 依赖 `api`；
- `client` 依赖 `controller`；
- `agent.acp` 依赖 CAP；
- `ClientRegistry` 依赖 `ChatController`；
- `common` 反向依赖业务包。

## 7. 旧包到新包映射

| 当前 | 目标 |
|---|---|
| `clientagent` | `gateway` |
| `controller.ChatController` | `api.cap.ChatController` |
| `controller.ClientToolController` | `api.cap.ClientToolController` |
| `controller.AgentController` | `api.agent.AgentController` |
| `controller.McpServerController` | `api.mcp.McpServerController` |
| `ws.AgentWebSocketHandler` | `api.ws.AgentWebSocketHandler` |
| `ws.WebSocketConfig` | `api.ws.WebSocketConfig` |
| `ws.WsSessionManager` | `client.transport.WsSessionManager` |
| `acp_agent.AcpAgent` | `agent.AcpAgent` |
| `acp_agent.AcpAgentRegistry` | `agent.AcpAgentRegistry` |
| `acp_agent.acp.*` | `agent.acp.*` |
| `acp_agent.impl.CliAgentAdaptor` | `agent.adapters.cli.CliAgentAdaptor` |
| `acp_agent.impl.SelfAgentAdaptor` | `agent.adapters.self.SelfAgentAdaptor` |
| `acp_agent.impl.CozeAgentAdaptor` | `agent.adapters.coze.CozeAgentAdaptor` |
| `core.AgentRequest` | `agent.AgentRequest` |
| `core.AgentResponse` | `agent.AgentResponse` |
| `core.AgentStreamSink` | `agent.AgentStreamSink` |
| `core.AgentSessionStore` | `agent.session.AgentSessionStore` |
| `core.ToolCallContext` | `client.ToolCallContext` |
| `client_registry.*` | `client.*` |
| `ClientRegistry.execute` | `ClientToolService` + `ToolInvoker` |
| `McpServerController` 内部工具逻辑 | `mcp.McpToolService` |

## 8. 迁移顺序

1. 根包 `clientagent` -> `gateway`，只改 package/import，不动逻辑；
2. `controller` 拆到 `api.cap` / `api.agent` / `api.mcp`；
3. `core` 拆到 `agent` / `agent.session` / `client`；
4. `acp_agent` 拆到 `agent` / `agent.acp` / `agent.adapters`；
5. `client_registry` -> `client`，拆出 `ToolInvoker`；
6. 抽 `ChatService` / `McpToolService` / `ClientToolService`；
7. 每次只做一类移动，移动后立即编译 + 跑 `/chat`、`/agents`、`/mcp` 三条链路。

## 9. 不建议的做法

- 不要给每个 Agent 建一个顶级包（`qwen/`、`claude/`、`coze/` 平铺），统一放 `agent.adapters`；
- 不要把 CAP DTO 放进 `agent`，也不要把 MCP DTO 放进 `client`；
- 不要让 Controller 直接 new Adaptor 或直接调 `RestClient`；
- 不要为了“分层”把 26 个类拆成 100 个接口；
- 不要把 `AcpAgent` 放进 `agent.acp`，它是端口，不是 ACP 专属。

## 10. 结论

当前最值得改的是三件事：

1. 根包改成 `gateway`，和模块名一致；
2. `controller` / `core` / `acp_agent` 按职责拆开；
3. `ClientRegistry` 拆出 `ToolInvoker`，`ChatController` 拆出 `ChatService`。

其余类先保持不动，避免一次性重构过大。
