# AcpAgentServer：外部 ACP Server 接入 Demo

这个模块演示：**一个外部 ACP Server 如何通过 WebSocket 接入 Agent Gateway。**

它不是 Agent 框架，也不实现 ACP 语义；它把 WS 帧透明代理到本机 `qwen --acp` 的 stdin/stdout。

## 1. 整体链路

```text
Client
  │ CAP
  ▼
AgentGateway
  │ ACP JSON-RPC over WebSocket
  ▼
AcpAgentServer  ws://localhost:8095/acp
  │ ACP JSON-RPC over stdio
  ▼
qwen --acp
```

对 Gateway 来说，`AcpAgentServer` 就是“外部 ACP Agent”。

## 2. 外部 ACP Server 的最小契约

外部 Agent 只需要：

1. 暴露一个 WebSocket 地址，例如 `ws://host:port/acp`；
2. 每个 WebSocket text frame 放一条 JSON-RPC 2.0 消息；
3. 处理 Gateway 发来的：
   - `initialize`
   - `session/new`
   - `session/prompt`
   - `session/cancel`（可选）
4. 用同一个 `id` 返回响应；
5. 主动发送 `session/update` 通知，驱动流式输出；
6. 需要权限时发送 `session/request_permission` 请求。

### 示例消息

`initialize`：

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1,"clientCapabilities":{},"clientInfo":{"name":"gateway","version":"0.1"}}}
```

响应：

```json
{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1,"agentCapabilities":{},"agentInfo":{"name":"my-agent","version":"0.1"}}}
```

`session/new`：

```json
{"jsonrpc":"2.0","id":2,"method":"session/new","params":{"cwd":"C:\\tmp","mcpServers":[]}}
```

`session/prompt`：

```json
{"jsonrpc":"2.0","id":3,"method":"session/prompt","params":{"sessionId":"sess-1","prompt":[{"type":"text","text":"你好"}]}}
```

流式通知：

```json
{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"sess-1","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"你"}}}}
```

最终 `session/prompt` 响应：

```json
{"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn"}}
```

## 3. 两种实现方式

### 方式 A：已有 stdio ACP Agent

如果 Agent 只会 `stdio`（例如 Qwen Code），像本模块一样做代理：

```text
WS text frame -> Agent stdin
Agent stdout -> WS text frame
```

本模块的 `AcpWebSocketHandler.startQwen()` 就是这一层。

### 方式 B：已有 WebSocket ACP Server

如果外部服务本身就说 ACP over WS：

- 不需要本模块的 Qwen 进程代理；
- 注册时直接把它的 `wsUrl` 交给 Gateway；
- Gateway 的 `WsAcpConnection` 会直接连它。

也就是说，`AcpAgentServer` 只是**一个可运行的参考实现**，不是接入的必经路径。

## 4. 接入自己的 Agent

只需要改一处：

```java
private Bridge startQwen(WebSocketSession session) throws IOException
```

- 换 command/args：适配其他 stdio ACP Agent；
- 换成远程连接：把 WS 帧转发到你的 ACP Server；
- 私有协议：在这里转换成 ACP JSON-RPC。

其余部分（WS 入口、AutoRegister）不用改。

## 5. 注册到 Gateway

自动注册配置：

```yaml
gateway:
  admin-url: 'http://localhost:8080'
  auto-register: true

agent:
  id: 'local-ws-qwen'
  public-ws-url: 'ws://localhost:8095/acp'
```

启动后等价于：

```bash
curl -X POST http://localhost:8080/admin/agents \
  -H "Content-Type: application/json" \
  -d '{"agentId":"local-ws-qwen","wsUrl":"ws://localhost:8095/acp"}'
```

如果不想自动注册，设置：

```yaml
gateway:
  auto-register: false
```

然后手动 POST。

## 6. 启动与验证

```powershell
$env:AGENT_ACP_API_KEY = '<key>'
mvn -q -pl AcpAgentServer spring-boot:run
```

Gateway 默认 8080，AcpAgentServer 默认 8095。

调用：

```bash
curl -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -d '{"v":"0.1","id":"1","type":"chat","payload":{"clientId":"agent-client","prompt":"只回复三个字：WS测试","agent":"local-ws-qwen"}}'
```

## 7. 限制

- 一个 WS 连接对应一个 Qwen 进程，没有连接池；
- 没有鉴权，不能暴露公网；
- 没有周期健康检查/自动注销；
- 只用于演示接入流程，不是生产 Agent Server；
- Qwen 0.26 ACP 下客户端 MCP 工具仍有限制，文本链路不受影响。
