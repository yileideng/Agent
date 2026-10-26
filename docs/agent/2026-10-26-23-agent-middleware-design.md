# 客户端 Agent 中间件技术方案

> 目标读者：本项目开发者 / 架构评审
> 范围：把当前 demo 演进为可支撑多客户端接入的 Agent 中间件
>
> **文档定位**：本文是实现层设计与现状评估。产品定位、产品形态、CAP/AAP 协议与传输模型的最新方案见 [2026-10-27-00-agent-gateway-design.md](./2026-10-27-00-agent-gateway-design.md)，两者配合阅读。

---

## 1. 背景与目标

当前 demo 已验证的核心链路：

```
客户端工具注册 → Agent 通过 MCP 发现工具 → 模型自主选择 → 中间件回调客户端执行 → 结果回传模型
```

要把它做成中间件，需要满足三个目标：

| 编号 | 目标 | 关键要求 |
|---|---|---|
| G1 | **复用已有 Agent 能力** | 不重写 Agent loop，可替换 Agent 实现（Claude Code / Qwen Code / 扣子 / 自研） |
| G2 | **给客户端提供标准化接入平台** | 客户端只需注册工具 + 提供回调，即可让 Agent 操作客户端能力（改笔记、选图、写文案、发布） |
| G3 | **多客户端接入且相互隔离** | 小红书、抖音等多个客户端的工具、会话、数据、额度必须隔离 |

一句话定位：**中间件 = Agent 运行时接入层 + 客户端工具协议网关 + 多租户隔离层**。

---

## 2. 方案选型结论

| 决策 | 选择 | 理由 |
|---|---|---|
| Agent ↔ 工具 协议 | **MCP（Streamable HTTP）** | 主流 CLI Agent（Qwen Code、Claude Code、Gemini CLI）原生支持；工具可动态下发；无需改造 Agent |
| Agent 接入方式 | **Adapter + 子进程/HTTP 接入** | 复用成熟 Agent harness，避免自研 loop 的效果差距 |
| 客户端工具执行位置 | **客户端进程内**（远程回调） | 工具涉及客户端私有状态（用户笔记、相册、登录态），只能客户端执行 |
| 隔离边界 | **Tenant（clientId）** | 工具、会话、数据、额度、密钥全部以租户为边界 |
| 会话归属 | **中间件持有** | Agent 可替换、可重启；换 Agent 不丢上下文 |

补充说明：

- **ACP 与 CAP 的关系**：ACP（Agent Client Protocol）是“客户端宿主 Agent”的协议，当前 spec 主要面向编辑器，但客户端不限于编辑器；本项目把它泛化到各种客户端，用 CAP 承载客户端侧契约。当前 CAP 是 ACP 思路的泛化实现，不是 ACP 线协议兼容：CAP 是 HTTP/WS + `v/id/type/payload`，ACP 是 JSON-RPC 的 `initialize/session/prompt/update/request_permission`。若要对齐 ACP 生态，需要单独加 ACP 适配入口，而不是把 CAP 直接改名为 ACP。
- **为什么不让 Agent 直连客户端**：需要中间件做鉴权、工具裁剪、审计、限流、多租户路由，否则每个客户端都要自己对接每种 Agent。

---

## 3. 总体架构

```
┌──────────────────────────── 客户端（多租户） ────────────────────────────┐
│  小红书客户端           抖音客户端            其他客户端                  │
│   - ClientTool 实现      - ClientTool 实现      - ClientTool 实现         │
│   - 工具执行回调端点      - 工具执行回调端点      - 工具执行回调端点        │
└───────────┬────────────────────┬────────────────────┬───────────────────┘
            │ ①注册工具(带租户凭证)  │ ②会话消息(REST/SSE) │ ④工具回调(HMAC)
            ▼                    ▼                    ▼
┌──────────────────────────── 中间件控制面 ───────────────────────────────┐
│  API Gateway（鉴权/限流/租户解析）                                       │
│  ├── Tenant Registry（租户、凭证、回调白名单、配额）                       │
│  ├── Tool Catalog（租户维度工具定义、版本、启停）                          │
│  ├── Session Manager（会话、消息、Agent 绑定、记忆作用域）                  │
│  └── Admin API（租户管理、审计查询）                                      │
└─────────────────────────────────────────────────────────────────────────┘
            │ ③ MCP tools/list & tools/call（带 session token）
            ▼
┌──────────────────────────── 中间件数据面 ───────────────────────────────┐
│  MCP Hub：按 session token 解析租户 → 过滤工具 → 路由调用                  │
│  Tool Router：权限校验 → HMAC 签名 → 回调客户端 → 超时/重试/幂等           │
│  Agent Adapter Layer：self / cli / coze / 未来新增                       │
│  Sandbox Pool：每会话独立工作区 + 受限 CLI 容器                            │
└─────────────────────────────────────────────────────────────────────────┘
            │
            ▼
┌──────────── 存储 ────────────┐   ┌───────────────┐   ┌─────────────────┐
│ PostgreSQL（租户/工具/会话）   │   │ Redis（会话态） │   │ 对象存储（租户前缀）│
└──────────────────────────────┘   └───────────────┘   └─────────────────┘
```

### 3.1 当前代码的模块划分

代码已按"中间件 / Agent / 客户端"三个可独立部署的进程拆分，Agent 与客户端都可整体替换；另外有一个**由中间件团队提供的客户端 SDK 库**：

| 模块 | 端口 | 职责 | 关键包 |
|---|---|---|---|
| `AgentAdaptor` | 8080 | 中间件：适配器注册表、会话持有、工具目录、MCP 网关、`(clientId,endUserId)` 隔离 | `agent_adaptor`（含 `impl` 子包）、`core`、`client_registry`、`controller`、`ws` |
| `BrainAgent` | 8081 | 自研 Agent 服务：只做决策（返回客户端工具调用或最终输出），**无状态、不持有会话** | `api`、`service`、`controller` |
| `AgentClientSdk` | — | 客户端 SDK：统一注册、HTTP 回调/WebSocket、tool_call 分发、心跳与重连 | `com.agent.demo.agentsdk` |
| `Client` | 8082 | SDK 的示例消费者：声明工具、执行工具回调、向中间件发起对话 | `client`、`tool`、`controller` |

调用闭环：

```
Client(8082) --POST /chat--> AgentAdaptor(8080) --POST /v1/brain/chat--> BrainAgent(8081)
        ^                              |  (会话归属在此持有)
        |                              v
        +----POST /client/tools/execute----+  (工具由中间件按 clientId 路由回客户端执行)
```

`BrainAgent` 只依赖 HTTP 契约，后续可整体替换为 Qwen Code / Claude Code / 扣子；`Client` 只依赖注册与回调协议，可拆成任意语言的独立客户端。

### 3.2 启动方式（本地）

```bash
# 0) 首次构建：安装客户端 SDK 到本地 Maven 仓库（Client 依赖它）
mvn -q -DskipTests install
# 1) Agent 服务
mvn -pl BrainAgent   spring-boot:run     # http://localhost:8081
# 2) 中间件
mvn -pl AgentAdaptor spring-boot:run     # http://localhost:8080
# 3) 客户端（启动后自动注册工具，中间件未就绪会每 5s 重试）
mvn -pl Client       spring-boot:run     # http://localhost:8082

# 端到端验证：客户端 → 中间件 → 自研Agent → 工具回调
curl "http://localhost:8082/chat?prompt=我叫什么名字"
```

中间件默认适配器为 `self`（`agent.default-adaptor`），也可用 `?agent=cli` / `?agent=coze` 切换。

---

## 4. 核心抽象与数据模型

### 4.1 实体

| 实体 | 关键字段 | 说明 |
|---|---|---|
| `Tenant` | `tenantId`, `name`, `appKey`, `appSecretHash`, `callbackAllowlist`, `allowedAgents`, `quota` | 一个客户端 = 一个租户 |
| `ClientToolDef` | `tenantId`, `name`, `description`, `inputSchema`, `version`, `enabled` | 工具按租户存储，`(tenantId, name)` 唯一 |
| `AgentProfile` | `agentId`, `type`(cli/coze/self/…), `modelConfigRef`, `workspacePolicy` | Agent 能力描述，可被多租户引用 |
| `Session` | `sessionId`, `tenantId`, `endUserId`, `agentId`, `agentSessionRef`, `status`, `createdAt` | 中间件持有；`agentSessionRef` 是 Agent 侧会话 ID 映射 |
| `ToolInvocation` | `requestId`, `sessionId`, `toolName`, `arguments`, `status`, `result`, `latencyMs` | 工具调用审计单元 |
| `MemoryScope` | `(tenantId, endUserId)` | 记忆/画像的隔离键 |

### 4.2 关系要点

- 一个租户可以有多个 AgentProfile（如 `xiaohongshu-default`、`xiaohongshu-fast`）。
- 一个会话绑定唯一租户和一个 AgentProfile。
- 工具可见性 = `session.tenantId` 下的 `ClientToolDef`，与全局工具表无关。

---

## 5. 多租户隔离设计（G3 核心）

隔离必须覆盖五个维度，缺一不可：

### 5.1 会话隔离

- 创建会话返回：`sessionId` + **短期 session token**（如 JWT，含 `tenantId`/`sessionId`/`exp`）。
- Agent 的 MCP 配置使用 token：

```json
{
  "mcpServers": {
    "client-tools": {
      "httpUrl": "http://middleware/mcp",
      "headers": { "Authorization": "Bearer <session-token>" },
      "trust": true,
      "timeout": 60000
    }
  }
}
```

- `/mcp` 请求必须解析 token → 得到 `tenantId`，**不允许**从请求体或 URL 参数自报租户。

### 5.2 工具隔离

- `tools/list` 只返回当前租户注册的工具。
- 工具名冲突：注册表主键为 `(tenantId, name)`；对模型展示时可保持原名，由 session 上下文决定解析哪一条。
- `tools/call` 必须校验：该工具属于当前 session 的租户，否则返回 `isError`。

> 当前实现：`ClientRegistry` 已按 `(clientId, name)` 存储工具，双客户端同名工具互不覆盖；MCP `/mcp/{clientId}` 也只返回当前客户端的工具。后续升级为租户 token 后，`clientId` 从 token 解析而非调用方自报。

### 5.3 数据隔离

- 数据库：所有业务表带 `tenant_id`，查询强制带租户条件；必要时启用行级安全（RLS）。
- 缓存：key 前缀 `t:{tenantId}:...`。
- 对象存储：路径前缀 `s3://bucket/{tenantId}/...`。
- 日志/追踪：统一携带 `tenantId`、`sessionId`、`requestId`，便于审计与排查。
- 客户端工具执行的业务数据（用户笔记、相册）**不落在中间件**，只做透传。

### 5.4 运行时隔离（最容易被忽略）

CLI Agent 天然具备文件读写与 shell 能力，多租户下必须隔离：

- **每会话独立工作目录**（`workspace/{tenantId}/{sessionId}`），会话结束即销毁。
- **CLI 运行在容器/沙箱中**，只挂载本会话工作区；禁止访问宿主目录。
- **禁用或限制危险内置工具**：用 `--exclude-tools` 关闭 shell/文件写入，或使用 CLI 自带沙箱能力。
- **网络出口策略**：只允许访问模型端点和中间件，避免 Agent 访问内网。
- **资源限制**：CPU/内存/进程时长上限（`--max-tool-calls`、进程超时）。

### 5.5 额度与密钥隔离

- 每租户配额：QPS、并发会话数、日 token 用量、单次运行时长。
- 模型密钥：平台密钥（共享）或 BYOK（租户自带），按租户路由；密钥存 KMS/Vault，不进配置文件与仓库。
- 高成本 Agent（如强模型）可只对特定租户开放。

---

## 6. 工具接入协议（G2 核心）

### 6.1 工具注册

```
POST /v1/client-tools/register
Headers: X-Tenant-Id, X-Timestamp, X-Nonce, X-Signature(HMAC-SHA256)
Body: {
  "callbackUrl": "https://xiaohongshu.example.com/agent/tools",
  "tools": [
    {
      "name": "update_note",
      "description": "修改用户笔记",
      "inputSchema": {
        "type": "object",
        "properties": {
          "note_id": {"type": "string", "description": "要修改的笔记 ID"},
          "content": {"type": "string", "description": "新的笔记正文"}
        },
        "required": ["note_id", "content"]
      }
    },
    {
      "name": "pick_photos",
      "description": "从用户相册挑选图片",
      "inputSchema": {
        "type": "object",
        "properties": {
          "count": {"type": "integer", "description": "挑选的图片数量"}
        },
        "required": ["count"]
      }
    }
  ]
}
```

要点：

1. **鉴权**：HMAC 签名（appSecret）+ 时间戳 + nonce 防重放。
2. **回调地址白名单**：只允许租户已备案的域名，禁止任意 URL（防 SSRF）。
3. **版本管理**：工具定义带版本，支持灰度启用/停用。
4. **回调归属校验**：注册后可要求客户端完成一次 challenge 响应，确认地址可控。

### 6.2 工具调用

```
Agent(MCP tools/call)
      │  {name:"update_note", arguments:{...}} + session token
      ▼
MCP Hub：解析租户 → 校验工具可见 → 生成 requestId
      ▼
Tool Router：POST {callbackUrl}
      Headers: X-Tenant-Id, X-Request-Id, X-Timestamp, X-Signature
      Body:    {"tool":"update_note","arguments":{...},"sessionId":"...","endUserId":"..."}
      ▼
客户端执行（用户私域数据）→ 返回结果 JSON
      ▼
Tool Router：幂等去重（requestId）→ 审计落库 → 结果回传 MCP content
```

工程要求：

| 项 | 方案 |
|---|---|
| 超时 | 默认 60s；按工具可配 |
| 重试 | 只对幂等工具重试（GET 类查询）；写操作不自动重试 |
| 幂等 | 客户端按 `X-Request-Id` 去重 |
| 失败语义 | 业务失败返回 `isError: true` + 原因，让模型决定是否继续；不抛 5xx 给 Agent |
| 审计 | 每次调用记录参数摘要、耗时、结果状态（敏感字段脱敏） |

### 6.3 长任务（如"发布笔记"）

MCP 的 `tools/call` 是同步 RPC，长任务需要异步化：

1. `tools/call` 立即返回 `{"status":"pending","taskId":"..."}`；
2. 客户端完成后再调用 `POST /v1/tool-results`（带 HMAC）；
3. 中间件把结果写入会话事件流，由 Agent 的下一轮读取（或使用 MCP 的异步任务扩展）。

### 6.4 高风险操作与人工确认

发笔记、删笔记、付费类工具应标记 `riskLevel`：

- `riskLevel=high` 的工具，客户端可配置"执行前需用户确认"；
- 中间件在调用前插入确认事件（推给客户端 UI），确认后再回调执行。

---

## 7. Agent 复用设计（G1 核心）

### 7.1 Adapter 接口（已具备）

```java
public interface AgentAdaptor {
    String name();
    default String description() { return name(); }
    default List<String> capabilities() { return List.of("chat"); }
    default boolean available() { return true; }
    default String unavailableReason() { return null; }
    AgentResponse chat(AgentRequest request);
}

public record AgentRequest(String clientId, String userPrompt,
                           String clientPrompt, String sessionId) {}
```

新增 Agent 只需实现该接口并注册为 Bean，`AgentAdaptorRegistry` 按名称选择。
客户端通过 `GET /agents` 获取 `description / capabilities / available`，接入前即可知道平台上有哪些 Agent、各自能做什么、当前是否可用。

当前内置三个实现（放在 `agent_adaptor.impl` 子包下）：

| name | 说明 |
|---|---|
| `self` | 自研 Agent 服务，HTTP 调 `BrainAgent` 的 `/v1/brain/chat`，由中间件执行 Agent Loop（默认） |
| `cli` | 复用 Qwen Code 子进程（已验证），经 MCP 发现并调用客户端工具；Claude Code 需先适配认证参数与 mcp-config 格式 |
| `coze` | 扣子低代码智能体，能力在扣子平台配置 |

`self` 适配器在中间件内完成完整 Agent Loop：调用 Agent 得到 `CLIENT_TOOL_CALL` → `ClientRegistry` 回调客户端执行 → 结果回传 Agent → 直到 `FINAL_OUTPUT`，并受 `agent.self.max-steps` 限制。

会话状态统一由 `AgentSessionStore` 持有，不放在 Brain 或各 Adapter 的私有 Map 里：

- 键：`(adaptor, clientId, endUserId, sessionId)`，`clientId + endUserId` 是用户边界；
- 内容：当前只保留 `sessionId` + Agent 侧会话引用（CLI `session_id` / 扣子 `conversationId`）；消息历史/共享变量等接入真实模型时再补；
- TTL + 定时清理，替换为 Redis/DB 时只需改这一个类。

### 7.2 CLI Agent 接入（当前主路线）

每会话一条 CLI 子进程：

```
qwen -p <prompt> --output-format stream-json \
     --resume <agentSessionRef> \
     --append-system-prompt <租户定制提示> \
     --mcp-config <会话级 mcp 配置(含 session token)> \
     --exclude-tools <危险工具> --max-tool-calls N
```

- 工作目录：`workspace/{tenantId}/{sessionId}`（当前实现为 `{agent.cli.work-dir}/{clientId}/{sessionId}`，跨轮次复用）；
- MCP 地址：`{agent.cli.mcp-url}/{clientId}`，CLI 只能发现当前客户端的工具；
- 环境变量：仅注入本租户模型凭证；
- 进程超时（`agent.cli.timeout-seconds`）、输出上限、并发池按租户配额控制；
- mcp 配置文件每会话唯一（临时文件），进程结束即删除；超时会 `destroyForcibly` 回收进程。

### 7.3 Agent 可替换性保障

| 约束 | 做法 |
|---|---|
| 会话必须可迁移 | 消息历史存中间件；Agent 侧 session ref 仅作加速，丢失可回放 |
| 工具协议统一 | 只依赖 MCP，不依赖某个 CLI 私有能力 |
| 输出统一 | 适配为统一事件模型（`delta` / `thinking` / `tool_call` / `final`） |
| 降级 | Agent 故障时按租户配置切换备用 AgentProfile |

---

## 8. 对外 API

### 8.1 当前已实现（P0 验证版）

| 接口 | 归属 | 说明 |
|---|---|---|
| `GET /agents` | 中间件 | Agent 能力发现：返回默认 Agent 及每个 Agent 的描述、能力标签、是否可用 |
| `POST /chat` | 中间件 | CAP v0.1 对话入口：请求体 `{v,id,type:"chat",payload:{clientId,endUserId,sessionId?,prompt,agent?,clientPrompt?}}`；`Accept: application/json` 返回 `final`，`Accept: text/event-stream` 返回 `delta/thinking/final/error` SSE |
| `POST /v1/brain/chat` | BrainAgent | 自研 Agent 决策接口：入参 `clientId/endUserId/sessionId/newSession/userPrompt/clientPrompt`，返回 `CLIENT_TOOL_CALL`（含工具名与参数）或 `FINAL_OUTPUT` |
| `GET /chat?prompt=&agent=&sessionId=` | Client | 自研客户端的用户入口，内部以 `POST /chat` 信封转发中间件，返回 `final` 信封 |
| `POST /client/tools/execute` | Client | 工具回调端点；请求和响应都是 CAP 信封 |
| `POST /clients/register` | 中间件 | CAP v0.1 注册入口：`register_tools` 信封，包含 `clientType/baseCallbackUrl/tools` 等；非法返回 400 + `error` 信封 |
| `GET /clients` | 中间件 | 查询已注册客户端列表 |
| `GET /clients/{clientId}/tools` | 中间件 | 查询某个客户端的全部工具 |
| `GET /clients/{clientId}/tools/{name}` | 中间件 | 查询单个工具定义 |
| `DELETE /clients/{clientId}/tools/{name}` | 中间件 | 注销单个工具 |
| `DELETE /clients/{clientId}` | 中间件 | 注销该客户端全部工具 |
| `POST /mcp/{clientId}` | 中间件 | MCP 端点，按 URL 中的 `clientId` 裁剪工具与路由调用；CLI 的 mcp-config 直接拼 `{mcp-url}/{clientId}` |

### 8.2 生产化演进（带版本号与鉴权）

| 接口 | 说明 |
|---|---|
| `POST /v1/sessions` | 创建会话，返回 `sessionId`、`sessionToken`、`mcpUrl`、`eventStreamUrl` |
| `POST /v1/sessions/{id}/messages` | 发送用户消息 |
| `GET /v1/sessions/{id}/events` | SSE 流式返回模型输出与工具事件 |
| `POST /v1/client-tools/register` | 注册/更新客户端工具（签名鉴权） |
| `POST /v1/client-tools/{name}/result` | 异步工具结果回传 |
| `GET /v1/client-tools` | 查询本租户已注册工具 |
| `POST /mcp` | MCP 端点（需 session token） |
| `GET /health`、`GET /metrics` | 运维接口 |

管理面：租户 CRUD、配额调整、工具启停、审计查询（内部网段 + 管理员鉴权）。

---

## 9. 安全设计

| 威胁 | 风险 | 对策 |
|---|---|---|
| 任意注册工具 | 攻击者把自己的工具注入平台 | 注册需 HMAC 鉴权 + 租户审核 |
| 恶意 callbackUrl | SSRF 打内网 | 白名单域名 + 禁止私网 IP + DNS 重绑定防护 |
| `/mcp` 裸奔 | 任意人枚举/调用工具 | session token 鉴权，按租户裁剪 |
| 跨租户串数据 | 工具/会话/记忆串号 | `(tenantId, name)` 主键、上下文注入租户、RLS |
| CLI 文件系统越权 | 读到其他租户/宿主文件 | 沙箱 + 独立工作区 + 禁高危工具 |
| 提示注入滥用工具 | 模型被诱导调用敏感工具 | 工具 allowlist、riskLevel 确认、参数校验、速率限制 |
| 密钥泄露 | 模型额度被盗 | KMS/Vault、按租户分发、配置不入库不入仓 |
| 重放 | 伪造工具结果 | 时间戳 + nonce + requestId 去重 |

---

## 10. 可靠性与可观测性

- **链路追踪**：`traceId` 贯穿 会话 → Agent 进程 → MCP → 客户端回调。
- **核心指标**：会话数、Agent 时长、工具调用成功率/P95、租户配额使用率、MCP 连接失败率。
- **审计日志**：谁（租户/用户）、何时、调用了什么工具、参数摘要、结果状态。
- **告警**：工具回调失败率、Agent 进程异常退出、租户超配额、MCP 401 激增。
- **容灾**：会话状态落 Redis/DB；Agent 进程崩溃后可恢复 `agentSessionRef` 或按历史回放。

---

## 11. 演进路线

| 阶段 | 内容 | 产出 |
|---|---|---|
| **P0（当前）** | 单租户 demo：Adapter + MCP + 注册回调 | 已验证链路 |
| **P1** | 租户模型 + session token + 工具按租户隔离 + 注册鉴权 + 修静态状态 | 可支撑小红书、抖音两家接入 |
| **P2** | 会话持久化 + 流式取消/背压 + 异步工具 + 审计限流 | 可用于生产预演 |
| **P3** | CLI 沙箱池 + 多 Agent 路由/降级 + 可观测性完善 + HA | 对外商业化 |

---

## 12. 现有代码完成度评估

### 12.1 能力对照

| 能力 | 现状 | 完成度 | 主要差距 |
|---|---|---|---|
| Agent 抽象与可替换 | `AgentAdaptor` + `AgentAdaptorRegistry` + `AgentRequest`，self/coze/cli 三实现，`GET /agents` 能力发现 | **85%** | 缺少统一事件模型、降级路由、AgentProfile 配置化 |
| 自研 Agent | 独立 `BrainAgent` 服务，HTTP 决策 + 中间件侧 Agent Loop（`SelfAgentAdaptor`） | **60%** | 决策逻辑仍为确定性演示，未接真实模型 |
| Agent 复用（CLI） | `CliAgentAdaptor` 子进程 + `AgentSessionStore` 会话映射 + 鉴权参数 + 客户端级 MCP 配置 | **85%** | 无容器沙箱、无并发池、无 token 限额 |
| 客户端工具协议 | `ClientTool` 接口 + 注册/查询/注销/回调 + `(clientId,name)` 工具目录 | **75%** | 无鉴权、无签名、无版本、无幂等、无审计 |
| MCP 网关 | `initialize`/`tools/list`/`tools/call` 已跑通，按 clientId 裁剪工具与路由 | **70%** | 无 session token、无调用权限校验 |
| 多租户隔离 | 工具目录与会话已按 `clientId` 隔离，默认客户端可配置 | **25%** | 无鉴权（clientId 自报）、无配额、数据未隔离 |
| 会话管理 | `AgentSessionStore` 统一持有，按 `(adaptor,clientId,sessionId)` 隔离，TTL 清理；`/chat` 支持 sessionId 多轮 | **60%** | 无持久化、无历史回放、换 Agent 时历史未回放 |
| 数据隔离 | 无 | **0%** | 无 DB、无租户主键、无存储前缀 |
| 运行时隔离 | 每会话独立工作目录、MCP 按客户端裁剪 | **30%** | CLI 仍以宿主进程运行，可读写宿主目录，无沙箱 |
| 流式输出 | 支持 CAP SSE（`delta/thinking/final/error`）；CLI 逐事件，Self 仅 `final` | **65%** | 取消、背压、异步执行器未做 |
| 异步长任务 | 工具调用同步 HTTP | **0%** | 无 pending/taskId 机制 |
| 可观测性 | `System.out` 日志已带 clientId/sessionId | **20%** | 无 trace、无指标、无审计落库 |
| 安全 | 注册/`/mcp` 均无鉴权；key 明文写 yml | **15%** | SSRF、越权、密钥管理全部缺失 |
| 测试 | 无自动化测试 | **0%** | 仅手工 curl 验证 |

整体判断：**P0 验证完成并补上了客户端隔离与 Agent 复用的基础能力（约 50%），距离可商用中间件还差鉴权、会话持久化、沙箱、限流审计四块**。

### 12.2 遗留问题（按优先级）

| 优先级 | 问题 | 位置 | 影响 |
|---|---|---|---|
| P0 | 注册接口与 `/mcp` 无鉴权，`clientId` 仍由调用方自报 | [ClientToolController.java](../../AgentGateway/src/main/java/com/agent/demo/clientagent/controller/ClientToolController.java) / [McpServerController.java](../../AgentGateway/src/main/java/com/agent/demo/clientagent/controller/McpServerController.java) | 可冒充任意客户端；接入 token 后才能作为真实租户边界 |
| P0 | `callbackUrl` 未做域名白名单，存在 SSRF 风险 | [ClientRegistry.java](../../AgentGateway/src/main/java/com/agent/demo/clientagent/client_registry/ClientRegistry.java) | 可回调内网地址 |
| P0 | 用户数据放在静态字段 `Client.name` | [Client.java](../../Client/src/main/java/com/agent/demo/agentclient/client/Client.java) | 多客户端场景会互相污染（仅演示客户端） |
| P1 | CLI 在宿主直接运行，可读写宿主目录 | [CliAgentAdaptor.java](../../AgentGateway/src/main/java/com/agent/demo/clientagent/acp_agent/impl/CliAgentAdaptor.java) | 越权、逃逸；已有独立工作目录但未沙箱 |
| P1 | 模型 API Key 明文写在 `application.yml` | [application.yml](../../AgentGateway/src/main/resources/application.yml) | 泄露风险（仓库公开） |
| P2 | 会话来内存态，重启即丢 | [AgentSessionStore.java](../../AgentGateway/src/main/java/com/agent/demo/clientagent/core/AgentSessionStore.java) | 无法水平扩展/故障恢复 |
| P2 | 工具调用无超时/重试/审计 | Tool Router 缺失 | 生产不可用 |

已完成的历史问题：全局 `Map<name, tool>` 已被 `(clientId, name)` 工具目录替代；`/chat` 已支持 `sessionId` 多轮；`sessionMap` 无上限无过期已由 `AgentSessionStore` + TTL 清理解决；MockAgent 与进程内 Client 已拆除，`agent_brain` 与 `client` 已拆为独立模块。

### 12.3 建议的下一步

1. 引入 session token：`/mcp` 从 token 解析 `clientId`，注册接口改用 HMAC/租户凭证，取代自报 clientId。
2. `callbackUrl` 域名白名单 + 禁止私网 IP，补 SSRF 防护。
3. 会话持久化（Redis/PostgreSQL）+ 历史回放，支撑水平扩展与换 Agent 续聊。
4. 工具调用超时/重试/幂等/审计 + 租户配额与限流。
5. CLI 容器沙箱化（每会话工作区挂载、禁高危内置工具、网络出口限制）。
6. 密钥迁移到环境变量/KMS，仓库只保留模板配置。
7. 补自动化测试：注册校验、工具隔离、mock 闭环、CLI 冒烟。
