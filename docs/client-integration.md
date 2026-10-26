# 客户端接入指南（CAP v0.1）

> 目标读者：需要把客户端工具接入 Agent 网关的开发者
> 协议版本：CAP v0.1
> 适用范围：Java / JavaScript / Node / Python / Go / 移动端
> 关联文档：[Agent 网关产品与技术方案](./agent-gateway-design.md)、[客户端 Agent 中间件技术方案](./agent-middleware-design.md)、[Agent 如何发现并调用客户端工具](./agent-tool-discovery-and-calling.md)

---

## 0. 接入模型：三句话

1. **对话由客户端主动发起**：`POST /chat`，返回 `final` 信封。
2. **工具始终在客户端执行**：网关通过 HTTP 回调或 WebSocket 下发 `tool_call`，客户端执行后回 `tool_result`。
3. **客户端只需要一个 `endUserId` + 一份工具清单**：工具怎么实现、用什么语言，网关不关心。

```
客户端                         网关                         Agent
  |-- POST /chat -------------->|                            |
  |<-- final -------------------|-- AgentRequest ----------->|
  |                             |<-- tool_call --------------|
  |<-- tool_call ---------------|                            |
  |-- tool_result ------------->|-- ToolResult ------------->|
  |<-- final -------------------|<-- final ------------------|
```

---

## 1. 接入前准备

| 项 | 说明 | 示例 |
|---|---|---|
| `gateway` | 网关 HTTP 地址 | `http://localhost:8080` |
| `wsUrl` | 网关 WebSocket 地址 | `ws://localhost:8080/ws/agent` |
| `clientId` | 客户端标识（一个应用一个） | `xiaohongshu` |
| `endUserId` | 终端用户标识（一个用户一个） | `u-123` |
| `transport` | 工具调用通道 | `HTTP_CALLBACK` 或 `WEBSOCKET` |
| `tools` | 本地工具清单 | `get_user_profile`、`save_note_draft` |

> 当前版本 `clientId` / `endUserId` 由客户端上报，网关还没有做 token 鉴权。生产接入前需要补 token，见第 12 节。

---

## 2. 选择传输

| 客户端类型 | 推荐传输 | 注册类型 | 工具调用通道 |
|---|---|---|---|
| 服务端后端（有公网/同网地址） | `HTTP_CALLBACK` | `SERVER` | 网关 → 客户端 HTTP |
| 浏览器 / PC App / 移动 App（NAT 后） | `WEBSOCKET` | `MOBILE` | 网关 → 客户端 WS |
| 只想快速验证 | CLI / 本地 Connector | 任意 | 由 Connector 处理 |

注意：**对话始终走 HTTP**，因为客户端主动出站请求天然穿透 NAT；WS 只解决“网关主动下发 `tool_call`”这一件事。浏览器不能暴露 HTTP 回调端点，所以只能用 WS。

---

## 3. 协议基础

### 3.1 信封

所有消息统一为：

```json
{
  "v": "0.1",
  "id": "msg-7f3c...",
  "type": "chat",
  "payload": {}
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `v` | 是 | 协议版本，当前 `0.1` |
| `id` | 是 | 消息 ID（字符串），响应原样带回 |
| `type` | 是 | 消息类型 |
| `payload` | 是 | 类型相关负载 |

约定：

- 编码统一 UTF-8；HTTP 请求头 `Content-Type: application/json`。
- `sessionId`、`chatId`、`callId` 都是**不透明字符串**，客户端不要解析，原样回传。
- 时间单位统一毫秒（`deadlineMs`）。

### 3.2 消息总表

| type | 方向 | 通道 | 作用 |
|---|---|---|---|
| `register_tools` | C→S | HTTP | 注册工具目录 |
| `tools_ack` | S→C | HTTP | 注册结果 |
| `chat` | C→S | HTTP | 发起一轮对话 |
| `final` | S→C | HTTP | 本轮最终结果 |
| `hello` | C→S | WS | 绑定连接与用户 |
| `hello_ack` | S→C | WS | 连接确认 |
| `tool_call` | S→C | HTTP 回调 / WS | 调用客户端工具 |
| `tool_result` | C→S | HTTP 回调 / WS | 回传工具结果 |
| `error` | S→C | HTTP / WS | 错误 |
| `ping` / `pong` | 双向 | WS | 心跳 |

---

## 4. 工具定义与注册

### 4.1 工具定义

推荐使用标准 JSON Schema：

```json
{
  "name": "save_note_draft",
  "description": "把标题、正文、话题标签保存为草稿",
  "inputSchema": {
    "type": "object",
    "properties": {
      "title":   { "type": "string" },
      "content": { "type": "string" },
      "topics":  { "type": "array", "items": { "type": "string" } }
    },
    "required": ["title", "content"]
  }
}
```

补充字段：

- `callbackPath`：仅 HTTP 客户端使用，表示该工具的回调路径；不传则使用客户端的 `defaultCallbackPath`。
- `inputSchema` 是必填项；无参数工具写 `{"type":"object","additionalProperties":false}`。

### 4.2 注册请求

`POST /clients/register`

```json
{
  "v": "0.1",
  "id": "reg-1",
  "type": "register_tools",
  "payload": {
    "clientId": "xiaohongshu",
    "clientType": "SERVER",
    "baseCallbackUrl": "https://client.example.com",
    "defaultCallbackPath": "/client/tools/execute",
    "clientPrompt": "你是小红书创作助手",
    "tools": [
      {
        "name": "get_user_profile",
        "description": "获取当前登录用户资料",
        "inputSchema": {
          "type": "object",
          "properties": {},
          "required": []
        }
      }
    ]
  }
}
```

字段说明：

| 字段 | 必填 | 说明 |
|---|---|---|
| `clientId` | 是 | 客户端唯一标识 |
| `clientType` | 是 | `SERVER` 或 `MOBILE` |
| `baseCallbackUrl` | SERVER 必填 | 回调基地址，必须是 http/https |
| `defaultCallbackPath` | 否 | 默认回调路径，缺省由工具 `callbackPath` 决定 |
| `clientPrompt` | 否 | 注入给 Agent 的客户端系统提示词 |
| `tools` | 是 | 工具列表，不能为空 |

### 4.3 注册响应

```json
{
  "v": "0.1",
  "id": "reg-1",
  "type": "tools_ack",
  "payload": {
    "clientId": "xiaohongshu",
    "registered": 1
  }
}
```

注册是**全量覆盖**：同一 `clientId` 再次注册会替换旧工具目录。未列出的工具视为删除。

---

## 5. 对话

### 5.1 发起对话

`POST /chat`

```json
{
  "v": "0.1",
  "id": "chat-req-1",
  "type": "chat",
  "payload": {
    "clientId": "xiaohongshu",
    "endUserId": "u-123",
    "sessionId": null,
    "prompt": "帮我写一篇咖啡测评",
    "agent": "self",
    "clientPrompt": "你是小红书创作助手",
    "context": {
      "page": "editor",
      "selection": "冰美式"
    }
  }
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `clientId` | 是 | 当前客户端 |
| `endUserId` | 是 | 当前终端用户，用于会话隔离 |
| `sessionId` | 否 | 不传表示新会话；续聊传上一轮 `final.sessionId` |
| `prompt` | 是 | 用户输入 |
| `agent` | 否 | 指定 Agent；不传使用网关默认 |
| `clientPrompt` | 否 | 本轮覆盖客户端提示词 |
| `context` | 否 | 页面/选中内容等上下文；当前版本保留，网关暂未透传给 Agent |

### 5.2 最终结果

```json
{
  "v": "0.1",
  "id": "chat-req-1",
  "type": "final",
  "payload": {
    "sessionId": "xiaohongshu:u-123:9f2c...",
    "chatId": "chat-1",
    "agent": "self",
    "output": "……"
  }
}
```

客户端需要保存：

- `sessionId`：下一轮续聊；
- `chatId`：本轮所有 `tool_call` / `tool_result` 的关联键。

### 5.3 多轮与多用户

- **多轮**：同一 `endUserId` + 同一 `sessionId` 连续 `chat`；网关当前只保留会话引用，消息历史待补。
- **多用户**：不同 `endUserId` 的会话完全隔离；`sessionId` 形如 `clientId:endUserId:uuid`，客户端不要手工拼接。
- **多客户端**：同一网关可同时接入多个 `clientId`，工具目录和会话互不影响。

---

## 6. 工具调用

### 6.1 tool_call

HTTP 回调 body 与 WS 帧使用同一个信封：

```json
{
  "v": "0.1",
  "id": "call-1",
  "type": "tool_call",
  "payload": {
    "clientId": "xiaohongshu",
    "endUserId": "u-123",
    "sessionId": "xiaohongshu:u-123:9f2c...",
    "chatId": "chat-1",
    "callId": "call-1",
    "tool": "get_user_profile",
    "arguments": {},
    "deadlineMs": 30000
  }
}
```

| 字段 | 说明 |
|---|---|
| `chatId` | 本轮对话；同一轮可能有多个工具调用 |
| `callId` | 本次调用；回传结果时必须原样带回 |
| `tool` | 工具名，对应注册时的 `name` |
| `arguments` | 参数对象 |
| `deadlineMs` | 等待结果的超时（毫秒） |
| `endUserId` / `sessionId` | SERVER 客户端多用户场景必须用它定位用户/会话 |

### 6.2 tool_result

```json
{
  "v": "0.1",
  "id": "call-1",
  "type": "tool_result",
  "payload": {
    "chatId": "chat-1",
    "callId": "call-1",
    "status": "success",
    "result": {
      "name": "张三"
    }
  }
}
```

失败：

```json
{
  "v": "0.1",
  "id": "call-1",
  "type": "tool_result",
  "payload": {
    "chatId": "chat-1",
    "callId": "call-1",
    "status": "error",
    "error": {
      "code": "TOOL_FAILED",
      "message": "上传图片失败"
    }
  }
}
```

规则：

1. `callId` 必须回传，否则网关无法唤醒等待中的 Agent。
2. `status` 只有 `success` / `error`。
3. `result` 是 JSON 值，推荐对象；不要返回未转义的字符串拼接结果。
4. `deadlineMs` 是硬截止；超时就返回 `status=error` + `code=TOOL_TIMEOUT`，不要无限挂起。
5. 同一 `chatId` 下可能有多个 `callId`，结果**顺序无关**；客户端可以并发执行，也可以串行执行。
6. 网关按 `callId` 收齐本轮结果后再继续 Agent；如果 Agent 需要顺序依赖，它会等前一个结果后再发下一个 `tool_call`。

### 6.3 多工具示例

一轮内连续两次调用：

```json
{"type":"tool_call","payload":{"chatId":"chat-1","callId":"call-1","tool":"get_name","arguments":{},"deadlineMs":30000}}
{"type":"tool_call","payload":{"chatId":"chat-1","callId":"call-2","tool":"update_name","arguments":{"updated_name":"李四"},"deadlineMs":30000}}
```

客户端分别回传两个 `tool_result`，`chatId` 相同、`callId` 不同。

---

## 7. HTTP 回调接入（SERVER）

### 7.1 注册

```http
POST /clients/register
Content-Type: application/json

{
  "v": "0.1",
  "id": "reg-1",
  "type": "register_tools",
  "payload": {
    "clientId": "xiaohongshu",
    "clientType": "SERVER",
    "baseCallbackUrl": "https://client.example.com",
    "defaultCallbackPath": "/client/tools/execute",
    "clientPrompt": "你是小红书创作助手",
    "tools": [
      {
        "name": "save_note_draft",
        "description": "保存笔记草稿",
        "inputSchema": {
          "type": "object",
          "properties": {
            "title": {"type": "string"},
            "content": {"type": "string"}
          },
          "required": ["title", "content"]
        }
      }
    ]
  }
}
```

### 7.2 回调端点

网关会向：

```
{baseCallbackUrl}{defaultCallbackPath}
```

POST `tool_call` 信封。客户端在 HTTP 响应体里返回 `tool_result` 信封即可。

Node/Express 示例：

```js
app.post("/client/tools/execute", async (req, res) => {
  const { chatId, callId, tool, arguments: args } = req.body.payload;
  try {
    const result = await handlers[tool](args);
    res.json({
      v: "0.1",
      id: req.body.id,
      type: "tool_result",
      payload: { chatId, callId, status: "success", result }
    });
  } catch (e) {
    res.json({
      v: "0.1",
      id: req.body.id,
      type: "tool_result",
      payload: {
        chatId, callId, status: "error",
        error: { code: "TOOL_FAILED", message: String(e) }
      }
    });
  }
});
```

Java Spring 示例（SDK 已封装）：

```java
@PostMapping("/client/tools/execute")
public String execute(@RequestBody String body) {
    return client.handleToolCall(body);
}
```

### 7.3 回调地址要求

- `baseCallbackUrl` 必须是客户端可被网关访问的地址（同 VPC、公网、或内网穿透）。
- **端口必须和客户端实际监听端口一致**。如果改了 `server.port` 却忘了改 `callback-base-url`，网关会回调到旧进程，表现为“对话一直等到超时”。
- 同一个 `clientId` 不要同时跑多个实例；如必须滚动发布，确保旧实例先下线，否则旧实例可能抢走回调。

---

## 8. WebSocket 接入（MOBILE / 浏览器 / NAT）

### 8.1 连接与 hello

> 工具注册仍走 HTTP `POST /clients/register`（`clientType=MOBILE`）；WS 只负责连接、hello 和工具调用下发。

连接：

```
ws://localhost:8080/ws/agent
```

连接建立后，客户端发送：

```json
{
  "v": "0.1",
  "id": "hello-1",
  "type": "hello",
  "payload": {
    "clientId": "xiaohongshu-app",
    "endUserId": "u-123"
  }
}
```

服务端返回：

```json
{
  "v": "0.1",
  "id": "hello-1",
  "type": "hello_ack",
  "payload": {
    "connectionId": "5d3e...",
    "clientId": "xiaohongshu-app",
    "serverVersion": "0.1"
  }
}
```

### 8.2 工具调用

网关通过同一条连接下发：

```json
{
  "v": "0.1",
  "id": "call-1",
  "type": "tool_call",
  "payload": {
    "clientId": "xiaohongshu-app",
    "endUserId": "u-123",
    "sessionId": "xiaohongshu-app:u-123:9f2c...",
    "chatId": "chat-1",
    "callId": "call-1",
    "tool": "get_user_profile",
    "arguments": {},
    "deadlineMs": 30000
  }
}
```

客户端回传同信封的 `tool_result`。

### 8.3 心跳与重连

- 客户端每 30 秒发 `{"v":"0.1","id":"...","type":"ping","payload":{}}`；
- 服务端回 `pong`；
- 断线后重连，重新发 `hello`；如果网关也重启了，重新 `register_tools`；
- 同一 `(clientId, endUserId)` 只保留最后一条连接；新连接会替换旧连接（旧连接关闭），这是为了避免旧版本客户端抢走工具调用。

### 8.4 浏览器最小示例

```js
const ws = new WebSocket("ws://localhost:8080/ws/agent");

ws.onopen = () => {
  ws.send(JSON.stringify({
    v: "0.1", id: crypto.randomUUID(), type: "hello",
    payload: { clientId: "web-xhs", endUserId: "u-123" }
  }));
};

const handlers = {
  get_user_profile: async () => ({ name: "张三" }),
  save_note_draft: async (args) => ({ draftId: "d-1", title: args.title })
};

ws.onmessage = async (event) => {
  const msg = JSON.parse(event.data);
  if (msg.type !== "tool_call") return;

  const { callId, chatId, tool, arguments: args } = msg.payload;
  const payload = { callId, chatId, status: "success" };
  try {
    payload.result = await handlers[tool](args);
  } catch (e) {
    payload.status = "error";
    payload.error = { code: "TOOL_FAILED", message: String(e) };
  }
  ws.send(JSON.stringify({
    v: "0.1", id: crypto.randomUUID(), type: "tool_result", payload
  }));
};
```

---

## 9. Java SDK

### 9.1 依赖

```xml
<dependency>
    <groupId>com.agent.demo</groupId>
    <artifactId>AgentClientSdk</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

首次构建先安装 SDK：

```bash
mvn -q -DskipTests install
```

### 9.2 HTTP 回调模式

```java
AgentClient client = AgentClient.builder()
        .gateway("http://localhost:8080")
        .transport(Transport.HTTP_CALLBACK)
        .callbackBaseUrl("http://localhost:8082")
        .clientId("xiaohongshu")
        .endUserId("u-123")
        .tool("save_note_draft", "保存笔记草稿", schema,
              args -> noteService.saveDraft(args))
        .build();

client.connect();
String finalJson = client.chat("帮我写一篇咖啡测评", null, null);
```

回调端点：

```java
@PostMapping("/client/tools/execute")
public String execute(@RequestBody String body) {
    return client.handleToolCall(body);
}
```

### 9.3 WebSocket 模式

```java
AgentClient client = AgentClient.builder()
        .gateway("http://localhost:8080")
        .transport(Transport.WEBSOCKET)
        .clientId("xiaohongshu-app")
        .endUserId("u-123")
        .tool("get_user_profile", "获取用户资料", Map.of(),
              args -> Map.of("name", "张三"))
        .build();

client.connect();
```

SDK 负责：注册、`hello`、心跳、断线重连、`callId` 关联、并发执行、`tool_result` 回传。

---

## 10. 其他语言接入

协议是 JSON，不依赖 Java。各语言只需要：

1. HTTP 客户端（注册 + 对话）；
2. WebSocket 客户端（WS 模式）；
3. JSON 编解码；
4. 工具函数表。

| 语言 | HTTP | WebSocket |
|---|---|---|
| JavaScript / TypeScript | `fetch` | 浏览器原生 `WebSocket` / Node `ws` |
| Python | `requests` / `httpx` | `websockets` / `aiohttp` |
| Go | `net/http` | `gorilla/websocket` / `nhooyr` |
| Swift | `URLSession` | `URLSessionWebSocketTask` |
| Kotlin/Android | OkHttp | OkHttp WebSocket |
| C# | `HttpClient` | `ClientWebSocket` |

如果需要，下一步可以基于本协议做 `sdk-js`（浏览器 WS + Node HTTP/WS），Java SDK 作为参考实现。

---

## 11. 错误与排障

### 11.1 错误信封

```json
{
  "v": "0.1",
  "id": "chat-req-1",
  "type": "error",
  "payload": {
    "code": "AGENT_ERROR",
    "message": "等待移动端工具执行结果超时(30000ms): get_name",
    "chatId": "chat-1"
  }
}
```

`chatId` 为可选字段；当前版本部分错误信封只包含 `code` / `message`，具体原因以 `message` 为准。

常见错误码：

| code | 含义 | 客户端动作 |
|---|---|---|
| `INVALID_REQUEST` | 参数/信封非法 | 修正请求 |
| `AGENT_ERROR` | Agent 或工具调用链失败 | 看 `message`，必要时重试 |
| `TOOL_TIMEOUT` | 工具执行超时 | 检查客户端是否回包 |
| `TOOL_FAILED` | 工具执行失败 | 看工具错误 |
| `TOOL_NOT_FOUND` | 工具未注册 | 检查注册清单 |
| `CLIENT_OFFLINE` | 客户端不在线 | 重连/重新注册 |
| `AUTH_FAILED` | 鉴权失败 | 检查 token（后续版本） |
| `RATE_LIMITED` | 触发限流 | 退避重试 |

> 当前版本部分工具错误会在 `/chat` 层统一表现为 `AGENT_ERROR`，具体原因在 `message` 里；后续会细化错误码。

### 11.2 排障检查清单

1. `GET /clients` 能看到你的 `clientId`、`clientType`、`tools` 吗？
2. SERVER 模式：`baseCallbackUrl + defaultCallbackPath` 能 `curl` 通吗？端口和实际监听端口一致吗？
3. WS 模式：连接后收到 `hello_ack` 了吗？30 秒 `ping` 有 `pong` 吗？
4. `tool_call` 的字段在 `payload` 里，不是顶层；`callId` / `chatId` 原样回传了吗？
5. `tool_result.payload.result` 是 JSON 对象吗？`status` 是 `success` / `error` 吗？
6. 同一 `clientId` 是否还有旧进程在跑（尤其是旧端口/旧版本）？
7. 对话超时时间是否大于工具超时？Java SDK 默认 180s；自研客户端建议 > 60s。
8. 中间件日志是否有 `[ClientRegistry]` / `[WsSessionManager]` / `[AgentSdk]` 记录？

### 11.3 常见坑

- **端口不一致**：`server.port=8083` 但 `callback-base-url` 还是 8082，回调到了旧进程。
- **旧连接抢回调**：同一用户同时连着旧客户端和新客户端，旧客户端不按新信封回包，网关一直等。
- **信封嵌套错误**：把 `tool` / `arguments` 放在顶层，而不是 `payload` 里。
- **回包缺少 `callId`**：网关无法关联结果，只能等超时。
- **`result` 是字符串**：客户端返回 `"{\"name\":\"张三\"}"` 而不是 `{"name":"张三"}`，Agent 侧会当成一段文本。
- **浏览器 token**：浏览器 WS 不能自定义 Header；生产环境用短期 token / subprotocol，不要把长期 token 放 URL。

---

## 12. 当前限制与后续

当前 CAP v0.1 是**可跑通的最小协议**，以下能力尚未实现：

| 能力 | 状态 |
|---|---|
| token 鉴权 | 未实现，`clientId/endUserId` 仍由客户端上报 |
| SSE / 流式对话 | 未实现，`/chat` 同步返回 `final` |
| 异步长任务 | 未实现，工具结果需在 `deadlineMs` 内同步返回 |
| 高风险工具确认 | 未实现 |
| 工具进度 | 未实现 |
| 工具注销 | 用全量 `register_tools` 覆盖 |
| 同用户多设备 | 不支持；同一 `(clientId,endUserId)` 最后连接生效 |
| 会话持久化 | 内存存储，重启丢失 |

---

## 13. 最小验收清单

接入完成后，至少验证：

- [ ] 注册成功，`GET /clients` 能看到工具；
- [ ] `POST /chat` 返回 `final` 信封；
- [ ] 一轮对话内能收到 `tool_call` 并回 `tool_result`；
- [ ] 同一轮多次工具调用（不同 `callId`）能正确汇总；
- [ ] 带上 `sessionId` 的多轮对话正常；
- [ ] 不同 `endUserId` 的会话不串；
- [ ] 工具超时/错误能返回 `error` 而不是无限挂起。

---

## 14. 相关文档

- [Agent 网关产品与技术方案](./agent-gateway-design.md)：产品定位、CAP/AAP 分层、传输模型
- [客户端 Agent 中间件技术方案](./agent-middleware-design.md)：实现层设计与现状评估
- [Agent 如何发现并调用客户端工具](./agent-tool-discovery-and-calling.md)：工具发现与回调机制
- [ClientRegistry 重构方案](./client-registry-redesign.md)：客户端注册与传输选择
