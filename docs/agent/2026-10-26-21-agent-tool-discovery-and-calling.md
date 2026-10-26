# Agent 如何发现并调用客户端工具

> 主题：Agent 是怎么知道客户端有哪些工具的、又是怎么调用这些工具的
> 关联文档：[2026-10-26-23-agent-middleware-design.md](./2026-10-26-23-agent-middleware-design.md)
>
> **历史说明**：本文记录的是早期 `ToolRegistry` / `parameters` / 每工具 `callbackUrl` 的方案。
> 当前实现已改为 `ClientRegistry` + `inputSchema` + 统一 CAP 信封，接入请看 [2026-10-27-00-客户端接入指南.md](../user/2026-10-27-00-客户端接入指南.md)。

---

## 一、核心结论

**中间件是唯一的工具真相源，Agent 永远不直接对接客户端。**

客户端把工具清单上报给中间件后，工具的发现与调用都经中间件路由。项目中两类 Agent 的机制不同：

| Agent 类型 | 工具发现方式 | 工具调用方式 |
|---|---|---|
| CLI Agent（Qwen Code / Claude Code） | MCP `tools/list` 动态获取 | MCP `tools/call` → 中间件回调客户端 |
| 自研 Agent（BrainAgent） | AgentBrain 自身决策（硬编码或模型） | 返回 `CLIENT_TOOL_CALL` → 中间件回调客户端 |

---

## 二、共同前提：工具目录由中间件持有

### 2.1 客户端注册工具

客户端启动后，把本地工具清单 + 回调地址上报给中间件：

```
POST /client-tools/register
{
  "clientId": "agent-client",
  "callbackUrl": "http://client:8082/client/tools/execute",
  "tools": [
    {
      "name": "get_name",
      "description": "获取当前登录用户的姓名，回答与用户身份相关的问题时调用",
      "parameters": { "name_query": "string" }
    }
  ]
}
```

### 2.2 工具定义模型

中间件 `ToolRegistry` 按 `(clientId, name)` 存储工具，每条定义都带 `callbackUrl`（客户端本地执行工具的回调地址）：

```
ToolDefinition(clientId, name, description, parameters, callbackUrl)
```

关键点：
- 工具目录的**唯一真相源是中间件**，Agent 不直接问客户端有哪些工具
- 每条工具自带 `callbackUrl`，中间件据此路由调用
- Agent 永远不直接访问客户端，所有调用都经中间件转发

---

## 三、CLI Agent：通过 MCP 动态发现

这是主流 Agent（Qwen Code / Claude Code 等）的标准方式。中间件把工具目录暴露成一个 MCP Server，端点是 `POST /mcp/{clientId}`。

### 3.1 完整链路

```
1. CLI 启动，mcp-config 指向 http://middleware:8080/mcp/{clientId}
2. MCP 握手：initialize
3. MCP tools/list  →  中间件返回该 clientId 下的工具清单
                        （name / description / inputSchema）
4. 模型读到工具定义，自主决定是否调用、调哪个、传什么参数
5. MCP tools/call {name, arguments}
       →  中间件 ToolRegistry.execute(clientId, name, args)
6. ToolRegistry 按工具的 callbackUrl HTTP 回调客户端
       →  客户端本地执行工具  →  返回结果
7. 中间件把结果包装成 MCP content 还给 CLI
       →  模型拿到结果继续推理
```

### 3.2 中间件的 MCP 实现

`McpServerController` 处理标准 JSON-RPC 请求：

| MCP 方法 | 中间件行为 |
|---|---|
| `initialize` | 协议握手，返回能力声明 |
| `tools/list` | 从 `ToolRegistry.list(clientId)` 取该客户端工具，转成 JSON Schema |
| `tools/call` | 调 `ToolRegistry.execute(clientId, name, args)`，结果包装成 MCP content |
| `ping` | 健康检查 |

`tools/list` 返回的工具结构示例：

```json
{
  "name": "get_name",
  "description": "获取当前登录用户的姓名...",
  "inputSchema": {
    "type": "object",
    "properties": { "name_query": { "type": "string" } },
    "required": ["name_query"]
  }
}
```

### 3.3 特点

- **工具发现与调用决策完全由 Agent（CLI 内的模型）完成**，中间件只负责"工具目录 + 路由回调"
- CLI 只能看到当前 `clientId` 下的工具，天然做到租户隔离
- 模型根据 `description` 和 `inputSchema` 自行判断是否调用、传什么参数

---

## 四、自研 Agent：决策在 Agent，执行在中间件

自研 Agent（BrainAgent）是独立 HTTP 服务，不走 MCP。Agent Loop 由中间件的 `SelfAgentAdaptor` 驱动。

### 4.1 完整链路

```
1. SelfAgentAdaptor 把 {clientId, sessionId, userPrompt}
      POST 给 AgentBrain /v1/brain/chat
2. AgentBrain 返回两种结果之一：
     - FINAL_OUTPUT：最终回答，结束
     - CLIENT_TOOL_CALL：带 toolName + toolParameters，表示"我要调这个工具"
3. 中间件拿到 CLIENT_TOOL_CALL
      → ToolRegistry.execute(clientId, toolName, args)
      → 按 callbackUrl 回调客户端执行
      → 结果塞回会话历史
4. 把工具结果作为下一轮输入再发给 AgentBrain
      循环直到 FINAL_OUTPUT 或达到 max-steps
```

### 4.2 AgentBrain 的决策契约

`SelfAgentAdaptor` 与 `AgentBrain` 之间的 HTTP 契约：

**请求** `BrainChatRequest`：

```java
record BrainChatRequest(String clientId, String sessionId,
                        boolean newSession, String userPrompt, String clientPrompt) {}
```

**响应** `BrainChatResponse`：

```java
record BrainChatResponse(String type, String toolName,
                         Map<String, String> toolParameters, String output) {}
```

- `type = CLIENT_TOOL_CALL`：需要 `toolName` + `toolParameters`
- `type = FINAL_OUTPUT`：需要 `output`

### 4.3 特点

- **"知道有哪些工具"是 AgentBrain 自己的事**
- 当前 demo 是确定性逻辑（硬编码首轮调 `get_name`）
- 换成真实模型时，需要把该客户端的工具 schema 作为上下文传给模型，由模型决定调哪个工具
- 中间件 `SelfAgentAdaptor` 只负责执行 Agent Loop（调 Agent → 调工具 → 回传结果 → 再调 Agent）

---

## 五、两条链路的共同模式

```
Agent  ──(想调工具)──>  中间件 ToolRegistry  ──(HTTP回调)──>  客户端本地执行
  ^                                                            |
  └────────────────────  工具结果  ────────────────────────────┘
```

### 5.1 不变的规则

1. **Agent 只看到工具定义，不直接接触客户端**
   - CLI 链路：通过 MCP `tools/list` 拿到 schema
   - 自研链路：AgentBrain 自己产出工具调用意图

2. **中间件按 `clientId` 路由**
   - 工具目录按 `(clientId, name)` 隔离
   - 调用时中间件查对应工具的 `callbackUrl`，回调正确的客户端

3. **工具必须在客户端本地执行**
   - 工具涉及用户私有数据（笔记、相册、登录态）
   - 中间件只做透传，不持有客户端业务数据

### 5.2 工具调用的实际执行

无论哪条链路，最终都走到 `ToolRegistry.execute`：

```java
public String execute(String clientId, String name, Map<String, Object> arguments) {
    ToolDefinition tool = get(clientId, name).orElseThrow(...);
    // POST {callbackUrl}  body: {clientId, tool, arguments}
    return restClient.post()
            .uri(tool.callbackUrl())
            .body(Map.of("clientId", clientId, "tool", name, "arguments", arguments))
            .retrieve()
            .body(String.class);
}
```

客户端的 `ClientToolController` 收到回调后，在本地执行工具并返回结果。

---

## 六、关键代码索引

| 关注点 | 文件 |
|---|---|
| 客户端注册工具入口 | `AgentAdaptor/.../client_tool/ToolRegistrationController.java` |
| 工具目录与回调执行 | `AgentAdaptor/.../client_tool/ToolRegistry.java` |
| MCP 工具发现与调用 | `AgentAdaptor/.../controller/McpServerController.java` |
| 自研 Agent Loop | `AgentAdaptor/.../agent_adaptor/impl/SelfAgentAdaptor.java` |
| 自研 Agent 决策 | `BrainAgent/.../service/SelfAgentBrain.java` |
| 客户端工具执行回调 | `Client/.../client/ClientToolController.java` |
