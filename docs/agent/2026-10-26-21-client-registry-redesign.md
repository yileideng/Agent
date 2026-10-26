# ClientRegistry 重构方案

> 主题：将 `ToolRegistry` 重构为 `ClientRegistry`，把"客户端"提升为一级注册实体，支持服务端客户端（HTTP 回调）与移动端客户端（WS 推送）两种传输方式
> 关联文档：[2026-10-26-23-agent-middleware-design.md](./2026-10-26-23-agent-middleware-design.md) / [2026-10-26-21-agent-tool-discovery-and-calling.md](./2026-10-26-21-agent-tool-discovery-and-calling.md)
>
> **历史说明**：本文记录重构过程，部分字段（`parameters`、`deviceId`）已过时；
> 当前协议与接入方式以 [2026-10-27-00-客户端接入指南.md](../user/2026-10-27-00-客户端接入指南.md) 为准。

---

## 一、背景与动机

### 1.1 现状

当前 `ToolRegistry` 只管理工具，每个 `ToolDefinition` 自带 `callbackUrl`：

```java
public record ToolDefinition(String clientId, String name, String description,
                             Map<String,String> parameters, String callbackUrl) {}
```

`ToolRegistry.register(clientId, callbackUrl, tools)` 把同一个 `callbackUrl` 灌进该客户端的所有工具。

### 1.2 存在的问题

| 问题 | 说明 |
|---|---|
| **"客户端"不是一等公民** | 注册表只存工具，没有客户端实体；客户端的类型、回调基地址、状态等元数据无处安放 |
| **callbackUrl 冗余** | 同一客户端的所有工具带同一份 callbackUrl，存储冗余且无法表达"这个客户端是手机 App" |
| **无法区分传输方式** | callbackUrl 隐含 HTTP 回调，移动端 App 没有公网地址，无法注册 |
| **工具级路径缺失** | 现实中同一客户端下不同工具可能走不同路径（如 `/tools/get_name`、`/tools/update_name`），当前所有工具共用一个 callbackUrl 表达不了 |

---

## 二、设计目标

1. 把"客户端"提升为一级注册实体，持有传输方式、回调基地址、工具列表等元数据
2. 工具只描述自己（名称、描述、参数、回调路径），回调基地址由客户端统一提供
3. 支持两种客户端类型：`SERVER`（HTTP 回调）和 `MOBILE`（WS 推送）
4. 工具调用时根据客户端类型自动选择传输方式
5. 与多用户/多设备 WS 路由方案配合：`ClientRegistry` 管"怎么传"，`WsSessionManager` 管"传给谁"

---

## 三、核心数据结构

### 3.1 ClientType 枚举

```java
/** 客户端类型，决定工具调用的传输方式 */
public enum ClientType {
    /** 服务端客户端：有公网可达地址，工具走 HTTP 回调 */
    SERVER,
    /** 移动端客户端：无公网地址，工具走 WebSocket 推送 */
    MOBILE
}
```

### 3.2 ClientRegistration（客户端注册信息）

```java
public record ClientRegistration(
    String clientId,              // 客户端唯一标识
    ClientType clientType,        // SERVER / MOBILE
    String baseCallbackUrl,       // 回调基地址（SERVER 必填，MOBILE 为空）
    String defaultCallbackPath,   // 默认回调路径（SERVER 可选，工具未指定 callbackPath 时使用）
    String clientPrompt,          // 注入给 Agent 的客户端定制系统提示词
    List<ToolDefinition> tools,   // 该客户端提供的工具列表
    Instant registeredAt,         // 注册时间
    String status                 // ONLINE / OFFLINE（MOBILE 跟踪 WS 连接状态，SERVER 恒为 ONLINE）
) {}
```

### 3.3 ToolDefinition（工具定义）

```java
public record ToolDefinition(
    String name,                  // 工具名（同一 clientId 内唯一）
    String description,           // 给模型看的工具描述
    Map<String, String> parameters, // 参数名 -> 类型
    String callbackPath           // 工具级回调路径（可选，缺省用客户端的 defaultCallbackPath）
) {}
```

**关键点**：`ToolDefinition` 不再带 `callbackUrl`，只带 `callbackPath`。完整回调地址由客户端基地址 + 工具路径拼接而成。

### 3.4 回调地址解析规则

```java
String resolveCallbackUrl(ClientRegistration client, ToolDefinition tool) {
    // SERVER: baseCallbackUrl + (tool.callbackPath ?: client.defaultCallbackPath)
    String path = tool.callbackPath() != null
            ? tool.callbackPath()
            : client.defaultCallbackPath();
    return client.baseCallbackUrl() + path;
}
```

示例：

| 客户端配置 | 工具 callbackPath | 最终回调地址 |
|---|---|---|
| base=`http://xhs.com`, defaultPath=`/client/tools/execute` | null | `http://xhs.com/client/tools/execute` |
| base=`http://xhs.com`, defaultPath=`/client/tools/execute` | `/tools/get_name` | `http://xhs.com/tools/get_name` |
| MOBILE 客户端 | 任意（忽略） | 不使用，走 WS |

---

## 四、ClientRegistry 职责

```java
@Component
public class ClientRegistry {

    private final ConcurrentMap<String, ClientRegistration> clients = new ConcurrentHashMap<>();
    private final RestClient restClient = RestClient.create();

    // ── 客户端注册与查询 ──

    /** 注册客户端（含工具列表），同一 clientId 重复注册会覆盖 */
    public ClientRegistration register(RegisterClientRequest request) { ... }

    public Optional<ClientRegistration> getClient(String clientId) { ... }

    public List<ClientRegistration> listClients() { ... }

    // ── 工具查询（按客户端隔离）──

    public List<ToolDefinition> listTools(String clientId) { ... }

    public Optional<ToolDefinition> getTool(String clientId, String toolName) { ... }

    // ── 注销 ──

    public boolean unregisterTool(String clientId, String toolName) { ... }

    public int unregisterClient(String clientId) { ... }

    // ── 工具执行 ──

    /**
     * 执行客户端工具：根据客户端类型选择传输方式
     * - SERVER: HTTP POST 到解析后的回调地址
     * - MOBILE:  通过 WsSessionManager 推送到 session 绑定的 WS 连接
     */
    public String execute(String clientId, String endUserId, String sessionId,
                          String toolName, Map<String, Object> args) {
        ClientRegistration client = getClient(clientId)
                .orElseThrow(() -> new IllegalArgumentException("客户端未注册: " + clientId));
        return switch (client.clientType()) {
            case SERVER -> executeViaHttp(client, toolName, args);
            case MOBILE -> executeViaWebSocket(clientId, endUserId, sessionId, toolName, args);
        };
    }
}
```

### 4.1 SERVER 执行（HTTP 回调）

```java
private String executeViaHttp(ClientRegistration client, String toolName,
                              Map<String, Object> args) {
    ToolDefinition tool = getTool(client.clientId(), toolName)
            .orElseThrow(() -> new IllegalArgumentException("工具未注册: " + toolName));
    String callbackUrl = resolveCallbackUrl(client, tool);
    return restClient.post()
            .uri(callbackUrl)
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("clientId", client.clientId(), "tool", toolName, "arguments", args))
            .retrieve()
            .body(String.class);
}
```

### 4.2 MOBILE 执行（WS 推送）

```java
private String executeViaWebSocket(String clientId, String endUserId, String sessionId,
                                   String toolName, Map<String, Object> args) {
    // 委托给 WsSessionManager：按 sessionId 找到对应的 WS 连接，推送 tool_call
    return wsSessionManager.invokeTool(clientId, endUserId, sessionId, toolName, args);
}
```

`WsSessionManager` 的职责见[第六章](#六与-ws-会话路由的配合)。

---

## 五、注册接口

`POST /client-tools/register` 演进为 `POST /clients/register`：

### 5.1 请求体

```json
{
  "clientId": "xiaohongshu-app",
  "clientType": "MOBILE",
  "baseCallbackUrl": null,
  "defaultCallbackPath": null,
  "clientPrompt": "你是小红书的创作助手...",
  "tools": [
    {
      "name": "get_name",
      "description": "获取当前登录用户的姓名",
      "parameters": { "name_query": "string" },
      "callbackPath": null
    },
    {
      "name": "update_note",
      "description": "修改用户笔记",
      "parameters": { "note_id": "string", "content": "string" },
      "callbackPath": "/tools/update_note"
    }
  ]
}
```

### 5.2 校验规则

| 字段 | SERVER | MOBILE |
|---|---|---|
| `clientId` | 必填，非空 | 必填，非空 |
| `clientType` | 必填 | 必填 |
| `baseCallbackUrl` | **必填**，http/https | 必须为空 |
| `defaultCallbackPath` | 可选 | 忽略 |
| `tools` | 必填，非空 | 必填，非空 |
| `tools[].callbackPath` | 可选 | 忽略 |

校验失败返回 400。

---

## 六、与 WS 会话路由的配合

`ClientRegistry` 回答"**这个客户端用什么传输**"，`WsSessionManager` 回答"**这条工具调用该推给哪条 WS 连接**"，两者解耦。

### 6.1 职责划分

| 组件 | 管什么 | 不管什么 |
|---|---|---|
| `ClientRegistry` | clientId → ClientType、baseCallbackUrl、tools | 具体哪条 WS 连接 |
| `WsSessionManager` | sessionId → wsConnectionId，WS 连接的上下线 | 客户端类型、工具定义 |

### 6.2 完整路由链路

```
execute(clientId, endUserId, sessionId, toolName, args)
  │
  ├─ ClientRegistry.getClient(clientId)
  │     → ClientType = SERVER / MOBILE
  │
  ├─ SERVER
  │     → resolveCallbackUrl(client, tool)
  │     → HTTP POST callbackUrl
  │
  └─ MOBILE
        → WsSessionManager.getConnection(sessionId)
        → 推送 {type:"tool_call", callId, name, args}
        → 等待 {type:"tool_result", callId, result}
```

### 6.3 多用户隔离保证

- MOBILE 执行时，工具调用按 `sessionId` 路由到发起该会话的 WS 连接
- 不会出现"用户 A 的对话推到用户 B 的 App 上执行"的越权问题
- 详见 [2026-10-26-21-agent-tool-discovery-and-calling.md](./2026-10-26-21-agent-tool-discovery-and-calling.md) 中"移动端回调方案"章节

---

## 七、对现有代码的影响

### 7.1 新增/修改文件

| 文件 | 动作 | 说明 |
|---|---|---|
| `client_tool/ClientRegistry.java` | **新增** | 替代 ToolRegistry，持有客户端注册信息 |
| `client_tool/ClientRegistration.java` | **新增** | 客户端注册 record |
| `client_tool/ClientType.java` | **新增** | SERVER / MOBILE 枚举 |
| `client_tool/ToolDefinition.java` | 修改 | 去掉 `clientId`、`callbackUrl`，新增 `callbackPath` |
| `client_tool/ToolRegistry.java` | **删除** | 功能由 ClientRegistry 替代 |
| `controller/ClientToolController.java` | 修改 | 注册接口改为 `/clients/register`，请求体适配新结构 |
| `controller/McpServerController.java` | 修改 | `tools/list` 调 `clientRegistry.listTools(clientId)` |
| `agent_adaptor/impl/SelfAgentAdaptor.java` | 修改 | `toolRegistry.execute(...)` → `clientRegistry.execute(clientId, endUserId, sessionId, ...)` |
| `Client` 模块注册请求体 | 修改 | 上报 `clientType`、`baseCallbackUrl` 等新字段 |

### 7.2 兼容性

- 当前 `Client` 模块（SERVER 类型）：`baseCallbackUrl=http://localhost:8082`，`defaultCallbackPath=/client/tools/execute`，工具不传 `callbackPath`，行为与重构前一致
- MCP `tools/list` 返回的工具结构不变（name/description/inputSchema），对 Agent 透明

---

## 八、迁移步骤

1. 新增 `ClientType`、`ClientRegistration`，改造 `ToolDefinition`
2. 新增 `ClientRegistry`，把 `ToolRegistry` 的逻辑搬过来并扩展传输方式分支
3. 修改 `ClientToolController` 的注册接口与校验
4. 修改 `McpServerController`、`SelfAgentAdaptor` 改用 `ClientRegistry`
5. 删除 `ToolRegistry`
6. 修改 `Client` 模块的注册请求体，上报 `clientType=SERVER` + `baseCallbackUrl`
7. 编译验证 + 端到端测试（SERVER 链路回归）
8. （后续）实现 `WsSessionManager` + MOBILE 链路
