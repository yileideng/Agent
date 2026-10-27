# AcpAgentServer：本地 Qwen Code WS ACP Server

> 用途：模拟外部 ACP Agent Server，验证 Gateway 的 WS 注册 + ACP 调用链路
> 模块：`AcpAgentServer`
> 默认地址：`ws://localhost:8095/acp`

## 1. 这个模块做什么

```text
Gateway
  │  ACP JSON-RPC over WebSocket
  ▼
AcpAgentServer  (Spring Boot + WebSocket)
  │  ACP JSON-RPC over stdio
  ▼
qwen --acp
```

`AcpAgentServer` 是一个**透明代理**：

- 每个 WS 连接启动一个本地 `qwen --acp` 进程；
- WS text frame 写入 Qwen stdin；
- Qwen stdout 的 JSON-RPC 行发回 WS；
- WS 关闭时关闭 Qwen 进程。

它不是生产级 Agent Server，只用于本地联调。

## 2. 为什么需要它

Gateway 的外部注册只接受 WS：

```json
{
  "agentId": "local-ws-qwen",
  "wsUrl": "ws://localhost:8095/acp"
}
```

需要一个真实 WS ACP Server 才能验证：

- `initialize`
- `session/new`
- `session/prompt`
- `session/update`
- 多轮会话

## 3. 配置

`AcpAgentServer/src/main/resources/application.yml`：

```yaml
server:
  port: 8095

gateway:
  admin-url: 'http://localhost:8080'
  auto-register: true

agent:
  id: 'local-ws-qwen'
  public-ws-url: 'ws://localhost:8095/acp'

qwen:
  command: 'D:\nodejs\node_global\qwen.cmd'
  node-path: 'D:\nvm\v22.14.0'
  auth-type: openai
  api-key: '<demo-key>'
  base-url: 'https://dashscope.aliyuncs.com/compatible-mode/v1'
  model: qwen-plus
  approval-mode: default
```

启动：

```powershell
$env:AGENT_ACP_API_KEY = '<key>'
mvn -q -pl AcpAgentServer spring-boot:run
```

> demo 配置里直接复用 Gateway 的 key；也可以改成环境变量，或在 AcpAgentServer 进程里设置
> `AGENT_ACP_API_KEY` / `OPENAI_API_KEY`。Qwen 拿不到 key 时会在 `session/new` 返回
> `Authentication required: Missing API key`。

启动后会自动向 Gateway 注册：

```text
POST http://localhost:8080/admin/agents
{"agentId":"local-ws-qwen","wsUrl":"ws://localhost:8095/acp"}
```

行为：

- 启动时最多尝试 3 次，每次间隔 2 秒；
- 成功或 409 已存在即停止；
- 注册失败只打印日志，不阻塞 Agent Server 启动；
- 不做周期重放、不自动注销，保持 demo 简单。

## 4. 调用

自动注册完成后直接调用：

```powershell
curl -X POST http://localhost:8080/chat `
  -H "Content-Type: application/json" `
  -H "Accept: application/json" `
  -d '{"v":"0.1","id":"1","type":"chat","payload":{"clientId":"agent-client","prompt":"只回复三个字：WS测试","agent":"local-ws-qwen"}}'
```

## 5. 验证结果

| 项 | 结果 |
|---|---|
| AcpAgentServer 启动 WebSocket `/acp` | 通过 |
| 启动后自动注册到 `http://localhost:8080/admin/agents` | 通过 |
| Gateway 注册 `ws://localhost:8095/acp` | 201 |
| `/agents` 显示 `local-ws-qwen` | 通过，available=true |
| 非流式 `/chat` | 通过，返回“WS测试” |
| SSE `/chat` | 通过，delta 逐段到达 |
| 同一 session 多轮 | 通过，能回答“你刚才让我回复 WS测试” |
| 无 WS Server 时 | Gateway 返回 `ACP_ERROR: 连接 ACP WS 失败` |

## 6. 限制

- 一个 WS 连接对应一个 Qwen 进程，没有连接池；
- 没有鉴权，不能直接暴露公网；
- 只支持文本链路；客户端 MCP 工具仍受 Qwen 0.26 ACP 工具注册限制；
- 仅用于本地验证，不是生产 Agent Server；
- 进程启动失败时直接关闭 WS，不返回结构化 ACP 错误。

## 7. 认证失败排查

如果看到：

```text
Authentication required: Missing API key for openai auth
```

说明 AcpAgentServer 启动时没有拿到 key。检查：

1. `AcpAgentServer/src/main/resources/application.yml` 的 `qwen.api-key` 是否为空；
2. 或启动 AcpAgentServer 的进程是否设置了 `AGENT_ACP_API_KEY` / `OPENAI_API_KEY`；
3. IDE 直接 Run 时，环境变量不会自动继承终端里的设置；
4. 不要只给 Gateway 设置 key，AcpAgentServer 是独立进程。

## 8. 代码位置

- `AcpAgentServerApplication`
- `AcpWebSocketHandler`：WS 入口 + Qwen 进程代理
- `WebSocketConfig`：注册 `/acp`
- `AutoRegister`：启动时注册到 Gateway
