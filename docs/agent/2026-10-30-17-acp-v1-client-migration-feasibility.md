# 把「客户端 ↔ AcpAgentFacade」改造成 ACP v1：可行性分析

> 分析范围：**只讨论客户端与 Facade 之间这一段线协议**（现有 CAP：`POST /chat` + SSE + `tool_call` 回调 → 目标 ACP v1）。Facade 与下游 Agent 之间（`AcpAgent` 内部接口 / 出口侧 ACP）不在范围内。
> 前置：[AcpAgentFacade 设计](./2026-10-30-16-acp-agent-facade-design.md)（协议侧方案）、[与 Zed ACP 对齐](./2026-10-27-13-acp-alignment-plan.md)（差距分析）
> 结论：**技术可行；但"原地重写"不是最优，推荐双栈 + 分客户端选传输**。wx-demo（C++）不建议直接上 WebSocket。

---

## 1. 结论摘要

| 维度 | 判定 | 说明 |
| --- | --- | --- |
| 协议可行性 | ✅ 可行 | ACP v1 的必选面（`initialize`/`session/new`/`session/prompt`/`session/update`/`session/cancel`）全部能在 Facade 侧实现，客户端侧也只需实现这 5 个方法 |
| 传输可行性 | ✅ 可行，但**必须选对** | wxWidgets 3.2.6 **没有 WebSocket 类**；wx-demo 现在用的是 `wxWebRequest` + 自写 SSE 解析 + 裸 Winsock 回调服务器。上 WS 要自写握手与帧编解码（500–800 行 C++） |
| 工具面 | ✅ 基本不用改 | 现有"网关代持 MCP + 客户端注册工具"在 ACP v1 下合法（见设计文档 §1.1），`register_tools` 降级为 Facade 内部方法，wx-demo 的 `AgentToolServer`（裸 socket + HTTP，301 行）可原样保留 |
| 客户端改动量 | ⚠️ 中等 | Java SDK 的 chat/SSE 路径基本重写（约 500 行）；wx-demo 约 350–450 行（走自定义传输）或 +500–800 行（走 WS） |
| 完整对齐（权限/取消/重连） | ⚠️ 成本集中在这 | 这三个是**跨层能力**，不是加字段：要动 `AcpAgent` 接口（无 `cancel`）、`ClientRegistry`（同步 `RestClient` 阻塞）、客户端 UI（权限弹窗） |
| 总体工作量 | **18–26 人日** | P0+P1 全链路（含文档与测试）；另需 P2 回放/桥接再加 5–8 人日 |
| 推荐路径 | **B：双栈** | 新写 ACP 入口，CAP 保留为兼容入口；先在 Java 客户端验证，再决定 wx-demo 是否迁 |

---

## 2. ACP v1 的硬约束逐条对照

只列规范的 **MUST / SHOULD** 项，因为可行性由硬约束决定，不由字段名决定。

| # | 约束（来源） | 现状 | 判定 |
| --- | --- | --- | --- |
| 1 | 消息必须是 JSON-RPC 2.0（`initialization`/`prompt-turn`） | CAP 是 `v/id/type/payload` 信封 | ❌ 需重写帧层 |
| 2 | `initialize` 必须先于任何会话方法，`protocolVersion` 是**整数** | 无 | ❌ 新增 |
| 3 | 能力"省略即不支持"，不得假设对端能力 | 无协商 | ❌ 新增 |
| 4 | `session/new` 返回 `sessionId`，`cwd` 必须绝对路径 | 客户端自报 sessionId，无 cwd | ❌ 新增 + 语义偏离（见 §4 R4） |
| 5 | `session/prompt` 的响应**只在整轮结束**返回 `stopReason` | `final` 事件自带全文 | ⚠️ 语义变化：正文只能靠 chunk 累积 |
| 6 | 流式内容只能走 `session/update` 通知（`agent_message_chunk` 等） | `delta`/`thinking` SSE 事件 | ✅ 映射直接 |
| 7 | Agent 在敏感工具前**MAY** 发 `session/request_permission`，客户端**必须**能回 outcome | 无权限面，靠提示词约束 | ❌ 新增（跨线程） |
| 8 | `session/cancel` 是通知；取消后 prompt 必须回 `cancelled` | 无取消 | ❌ 新增 |
| 9 | 工具调用由 Agent 执行并通过 `tool_call`/`tool_call_update` 上报 | 工具在客户端执行，无上报 | ⚠️ Facade 代上报（设计文档 D5） |
| 10 | 允许自定义传输，但必须保持 JSON-RPC 语义与生命周期 | HTTP + SSE（单向） | ⚠️ SSE 不能承载反向请求 → 必须换传输 |

**结论：第 1、2、4、7、8、10 条决定了这不是"改几个字段"，而是换一层协议实现。** 但换的都是薄层——业务链路（会话历史、增量注入、工具回调）一行不用动，这是可控的关键。

---

## 3. 影响面与改动量

### 3.1 各模块清单

| 模块 | 现有实现 | 需要做的 | 估行数 | 估时 |
| --- | --- | --- | --- | --- |
| `AgentGateway`（Facade 侧） | 无 ACP 入口 | 见设计文档 §8：P0 900–1200 行 + P1 400 行 | 1300–1600 | 8–11 人日 |
| `AgentClientSdk`（Java，702 行） | `register` + `/chat`（同步/SSE）+ 本地工具回调 | 新增 `AcpClientTransport`（帧/请求路由）、`AcpSessionClient`（initialize/new/prompt/update 分发）；`chat`/SSE 路径改成 `session/prompt`；`ToolHandler` 与工具回调保留 | ~500 新增 / ~250 替换 | 3–4 人日 |
| `Client`（Java 演示端） | `ClientChatController` 调 SDK | 改成 ACP 客户端示例（保留旧接口做对比） | ~150 | 1 人日 |
| `wx-demo`（C++，独立仓库） | `AgentClient.cpp` 797 行（wxWebRequest + SSE）、`SseParser` 77、`AgentToolServer` 301、`WinHttpStream` 188 | 对话路径换成 JSON-RPC + 传输；工具服务器不动；渲染路径（`AgentPanel` 502）只需改事件分发 | 350–450（自定义传输）/ +500–800（WS） | 3–5 人日 |
| 文档 | `docs/agent` 22 篇 | 受影响约 8–10 篇（协议、网关、流式、wx-demo 接入、论文相关） | — | 2–3 人日 |
| 测试 | `SharedConversationTest` 等 3 个测试类 | 新增 Facade 协议测试（§7 用例）+ 客户端契约测试 | ~400 | 1–2 人日 |

### 3.2 为什么 wx-demo 是最大变量

`wx-demo` 的真实约束（不是猜测，来自代码）：

- `AgentConfig.h:9` `GatewayBaseUrl = http://127.0.0.1:8080`；`AgentClient.cpp:89` 走 `/clients/register`，`:171` 走 `/chat`；
- SSE 靠 `request_.SetHeader("Accept","text/event-stream")`（`AgentClient.cpp:195`）+ 自写 `SseParser`，另外还有 `WinHttpStream.cpp`（188 行）绕开 `wxWebRequest` 的流式行为问题；
- 工具回调是一个**裸 Winsock HTTP 服务器**（`AgentToolServer.cpp:83-113`，监听 `127.0.0.1:18080`），自己解析 HTTP/1.1；
- 工程内 vendor 的是 **wxWidgets 3.2.6**，该版本**没有 WebSocket API**（`wxWebRequest` 也不暴露底层 socket 句柄）。

所以 wx-demo 迁 ACP 的真实成本不在协议理解，而在**传输客户端**：

| 传输选项 | wx-demo 侧工作量 | 评价 |
| --- | --- | --- |
| WebSocket（自写） | 握手（`Sec-WebSocket-Accept` 需 SHA-1 + Base64，得引 CryptoAPI/BCrypt）、帧编解码（掩码、分片、ping/pong）、重连 ≈ 500–800 行 | 能做，但一半工作量花在"重新实现一个 WS 库"上，与论文主题无关 |
| 第三方 WS 库（IXWebSocket / websocketpp / Boost.Beast） | 引入 + 编译约 1–2 人日，之后 ~200 行 | 可行；但 wx 工程已有 vendor 目录，再加一个大依赖需评估构建 |
| **自定义传输：TCP + 新行分隔 JSON-RPC** | 复用已有 socket 代码，约 120–200 行（只需逐行读 + JSON 解析；wx 自带 `wxJSON`？没有，需引入 nlohmann/json 单头或自写） | **推荐**：ACP v1 明确允许自定义传输，只要保持 JSON-RPC 语义。协议正确性不受影响 |
| stdio（客户端拉起桥接进程） | 需要随包分发一个 `acp-bridge`（Java 或 Go），wx 侧用 `wxProcess` 收发 ≈ 200 行 | 可行，但多一个部署物，答辩时解释成本高 |

> 这条结论值得单独强调：**"改用 ACP"不等于"改用 WebSocket"**。规范和实现要分开看，否则 wx-demo 会被一个与协议无关的传输问题拖住两周。

---

## 4. 六个真正的拦路点

按"会不会导致方案失败"排序，前两个是设计问题，后面是工程量。

### R1 工具模型：ACP 要求工具来自 MCP，而本项目让客户端"注册工具"

- **冲突**：ACP v1 的标准路径是客户端在 `session/new.mcpServers` 里给出 MCP Server；本项目的客户端只做两个 HTTP 端点（注册 + 回调）。
- **判定**：不冲突。`mcpServers` 是客户端**可选**提供的连接信息，Agent 可以自带工具（官方原文见设计文档 §1.1）。Facade 把客户端工具包成自己的 MCP Server，是"Agent 自带工具"的正当实现。
- **代价**：这是**扩展**而非标准路径，必须在论文和文档中标注清楚。严格的外部 ACP 客户端如果坚持自己提供 MCP Server，Facade 需要在 P1 支持合并（设计文档 D3）。
- **风险等级**：低（架构自洽），但**表述风险高**——说成"我们扩展了 ACP"没问题，说成"我们实现了 ACP 的工具模型"就会被质疑。

### R2 权限 UI：`session/request_permission` 需要客户端有"阻塞提问"的能力

- **现状**：wx-demo 的写操作（`add_component`/`connect_pins`/`set_output_pin`）**没有**任何确认 UI，Agent 直接改画布；之前只能靠系统提示词约束。
- **ACP 后**：客户端会周期性收到 `request_permission` 请求，必须回 `selected` 或 `cancelled`；不回就卡住整轮（Facade 30s 超时后按拒绝处理）。
- **工程量**：wx-demo 需要模态对话框 + 主线程同步（`CallAfter` + 条件变量），约 80–120 行 —— 不难，但**必须做**，否则工具全部被拒。
- **附带收益**：这正好补上此前"Agent 未经许可改画布"的问题，且是协议层强制，比提示词可靠。**这一点在论文里是可写的机制，不是补丁。**

### R3 取消：现有链路是同步阻塞的，`session/cancel` 打不断

- **现状**：`ClientRegistry.executeViaHttp()` 用同步 `RestClient`（`ClientRegistry.java:299-304`），工具调用线程会阻塞到客户端返回；`AgentStreamSink` 也没有取消入口。
- **ACP 后**：`session/cancel` 要求"停这一轮"，包括：停止下游 Agent、取消挂起的权限请求、丢弃后续 chunk、回 `cancelled`。
- **工程量**：把工具调用改成 `CompletableFuture` + 可取消（`ClientRegistry` 约 60 行改动），`AcpAgent` 加 `cancel`，CLI 适配器实现杀进程。属于 P1。
- **风险**：如果只做"标记取消 + 立即回响应"（P0 降级），下游仍在跑，结果被丢弃——行为可接受，但要在文档里写明"取消是 best-effort"。

### R4 身份与 `cwd`：ACP 没有 `clientId` / `endUserId`

- **现状**：会话隔离靠 `clientId:endUserId:sessionId` 前缀（`AgentSessionStore.java:79`），这两个值由 HTTP 请求体/查询参数自报。
- **ACP 后**：协议里没有租户概念。只能：
  1. 传输层握手解析（WS `Authorization` 头 / TCP 连接首帧 `_meta`）；
  2. `session/new` 的 `cwd` 兼作会话线索（不可靠，见下）。
- **`cwd` 问题**：规范要求绝对路径且 Agent 必须用它做相对路径基准。远程 Facade 拿不到客户端的文件系统。设计文档 D7 的结论是"接收但降级 + `_meta.cwdRemapped` 显式告知"。
- **工程量**：中（会话注册表 + 握手鉴权），且**这是安全边界**：不解决就等于"任何人连上就能用别人的 clientId"。

### R5 幂等与重放：ACP v1 没有请求去重

- **现状**：CAP 的 `chatId` + `callId` 让网关能识别"这次工具调用已经执行过"（`ConversationStore` 里 `tool_call` 状态 `running`/`ok`/`error`）。
- **ACP 后**：`session/prompt` 的 JSON-RPC `id` 只在连接内有效；断线重连后客户端重发同一段文本，会产生**第二次**工具执行（例如又加了一个 AND 门）。
- **这是真实风险**：画布类有副作用的工具，重复执行 = 用户看到重复元件。
- **应对**（三选一）：
  1. Facade 在 `session/update` 的 `messageId` 上做去重（规范允许 Agent 提供 `messageId`）；
  2. 工具层幂等：`add_component` 带客户端生成的 `requestId`，服务端按 ID 去重（推荐，改动小）；
  3. 文档声明"重连后不自动重放，客户端负责不重发未确认的 prompt"（最省事）。
- **论文价值**：这是个可写的"已知限制 + 工程对策"，比假装没有更可信。

### R6 客户端必须并发处理入站消息

- **要求**：`session/update`（通知）与 `session/request_permission`（请求）都会在 `session/prompt` 未返回时到达。客户端若在等 prompt 响应时阻塞读线程，会死锁（Facade 30s 后按拒绝处理，表现为"工具莫名失败"）。
- **现状**：wx-demo 的 SSE 是单请求单流，天然没有这个问题；改成 JSON-RPC 后**必须**改成"读线程只分发、业务线程处理"。
- **工程量**：小（架构上本来就该这样），但**最容易在实现时踩**，必须写进客户端接入文档。

---

## 5. 三档方案

| | A：全量迁移（CAP 下线） | **B：双栈（推荐）** | C：只做语义对齐（不改线协议） |
| --- | --- | --- | --- |
| 做法 | 所有客户端改 ACP，删 `/chat` 与 `/clients/register` | 新增 `/acp` 入口；CAP 保留，标记为 legacy | 保留 CAP，但补 `tool_call` 上报、权限、取消这些**语义** |
| 客户端改动 | 全部重写 | 按客户端逐步迁 | 无 |
| 论文说法 | "实现了 ACP v1" | "提供 ACP v1 入口，同时保留轻量兼容入口" | "借鉴 ACP v1 的权限/取消模型" |
| 风险 | 一次性破坏所有演示链路；答辩前不可控 | 双份维护成本；需明确主路径 | 仍然"不是 ACP"，协议贡献弱 |
| 工作量 | 26–34 人日 | 18–26 人日 | 5–8 人日 |

**推荐 B**，理由：

1. ACP 入口是**新代码**，不动现有链路 → 演示随时可回退（答辩风险最低）；
2. 可以先在 Java 客户端上验证协议正确性，wx-demo 保持现状直到传输方案定稿；
3. 双栈的成本主要是"两套入口的测试"，而这两个入口共享 `AgentRouter`/`ConversationCoordinator`/`ClientRegistry`，重复量比想象小；
4. 论文可以写"渐进式协议对齐"，这比"一次性替换"更像工程实践，也更好答辩。

**不推荐 A**：wx-demo 的传输改造（R1 之外的纯工程）会吃掉大部分时间预算，而它对论文贡献为零。

---

## 6. 迁移顺序

```
第 1 步  Facade P0（设计文档 §8）            —— 不碰任何客户端，最小 ACP 客户端验收
第 2 步  Java SDK 增加 ACP 客户端通道        —— 契约测试对齐（同一后端，两种协议行为一致）
第 3 步  Client 演示端切 ACP 通道            —— 第一个"真实客户端"跑通
第 4 步  wx-demo 传输选型（TCP-JSONL / WS）  —— 见 §3.2，建议 TCP-JSONL 先跑通
第 5 步  P1：权限 + 取消                     —— wx-demo 补权限弹窗；演示"Agent 改画布需授权"
第 6 步  文档收敛 + CAP 标记 legacy
```

**Feature flag**：`agent.acp.enabled`（默认 false）+ `agent.acp.transport`（`ws` / `tcp-jsonl`）。任何一步失败都能立刻回到 CAP，不影响既有演示。

**并行建议**：第 1 步与第 3 步可并行（Java 客户端只需要协议文档，不需要真实网关）；第 4 步必须等第 1 步验收通过。

---

## 7. 对论文的影响（必须重写的一段）

改造完成后，论文的贡献陈述要跟着改，否则会被评审判"过度声称"。

**不能再说**：

- ❌ "本文提出了 ACP 协议"（协议不是本文的）
- ❌ "CAP 是必要的协议创新"（已被 ACP v1 覆盖）

**应该说**（三条都能被代码和实验支撑）：

1. **Agent 聚合**：在 ACP v1 语义下实现"一个 Agent 身份、N 个异构后端"的多态替换，客户端零感知（`AcpAgentRegistry` + `AgentRouter`）。
2. **会话连续性**：同一 ACP session 内切换后端，按差量注入历史，客户端不重连、不感知（`SharedConversationTest` 可直接扩展成实验）。
3. **工具面升格**：客户端不实现 MCP，由网关把注册的工具升格为标准 MCP 工具，使任意下游 Agent 都能使用（`McpServerController` + `ClientRegistry`）。

**新增的可验证点**（这次改造带来的）：

- 权限语义：写操作必须经 `session/request_permission`，可统计"拦截率 / 用户拒绝率"；
- 取消时延：`session/cancel` → 停止下游的 P95 时延；
- 协议一致性：Facade 通过最小 ACP 客户端的 10 条用例（设计文档 §7.2）。

**风险提示**：如果导师/评委问"为什么不直接用 Zed 的 ACP 客户端连你的网关"，正确回答是：可以，`/acp` 就是为这个准备的；我们额外提供了 `register_tools` 这条更轻的接入路径，论文中标注为扩展。

---

## 8. 一周 POC 计划（决策前必做）

目标：用最小成本回答"值不值得做"，而不是先写代码再论证。

| 天 | 事项 | 产出 |
| --- | --- | --- |
| D1 | 写最小 ACP 客户端（Node，150 行），对**桩服务**跑 `initialize`/`session/new`/`session/prompt` | 证明协议理解无误（含 `protocolVersion` 整数、能力省略语义） |
| D2–D3 | Facade P0 骨架（WS + 三个方法 + `session/update` 映射） | Java 客户端能收到流式中文，`stopReason=end_turn` |
| D4 | 接 `tool_call` 上报（复用 `callId`） | 最小客户端能看到 `tool_call` → `completed` |
| D5 | wx-demo 传输验证：用 `AgentToolServer` 的 socket 经验，写 100 行 TCP-JSONL 客户端连 Facade | 判断 "TCP-JSONL 是否够用"（这是 wx-demo 是否迁移的关键证据） |
| D6–D7 | 决策会：按 §5 选档，冻结 P1 范围 | 一份 2 页决策记录（写进 `docs/agent`） |

**POC 失败的早期信号**（出现任一就应停下重新评估）：

- 最小客户端在 `session/prompt` 上收不到 chunk（说明下游 `chatStream` 与 emitter 的线程模型不兼容）；
- 工具调用无法定位到 ACP session（`sessionId` 没随 MCP URL 传下来）；
- wx-demo 的 TCP-JSONL 原型在 200 行内跑不通（说明传输成本被低估）。

---

## 9. 附录：CAP 与 ACP v1 的字段级差异

比 [对齐方案 §7](./2026-10-27-13-acp-alignment-plan.md) 更细，供实现时对照。

| 关注点 | CAP（现状） | ACP v1 | 迁移注意 |
| --- | --- | --- | --- |
| 帧 | `{v,id,type,payload}` | `{jsonrpc:"2.0",id,method,params}` / `{jsonrpc,id,result}` / `{jsonrpc,id,error}` | `id` 语义不同：CAP 是随请求带回的字符串，ACP 是连接内唯一（数字或字符串） |
| 版本 | `v:"0.1"` 字段 | `initialize.protocolVersion`（**整数**） | CAP 的版本字段要删掉，不能两边都带 |
| 建立会话 | 请求体里自报 `sessionId` | `session/new` 由 Agent 生成 | 客户端不能再自造 ID |
| 一轮对话 | `type:"chat"` + `prompt:string` | `session/prompt` + `prompt: ContentBlock[]` | 文本要包成 `[{"type":"text","text":...}]`；图片/资源块 P1 才支持 |
| 流式正文 | `type:"delta"` + `{chatId,seq,text}` | `session/update` → `agent_message_chunk` + `content:{type:"text",text}` | `seq` 不再需要（JSON-RPC 保证顺序）；`chatId` 不进协议 |
| 思考 | `type:"thinking"` | `agent_thought_chunk` | 客户端要决定是否展示（wx-demo 已有灰斜体样式） |
| 结束 | `type:"final"` + `{sessionId,chatId,agent,output}` | `session/prompt` 响应 `{stopReason}` | **`output` 不再提供**，必须由 chunk 累积 |
| 错误 | `type:"error"` + `{code,message}` | JSON-RPC `error` / `tool_call_update.status=failed` | 业务失败不要用 RPC error（见设计文档 §6.3） |
| 工具调用 | 网关 → 客户端 `type:"tool_call"` + `payload{callId,tool,arguments,deadlineMs}` | Agent → 客户端 `session/update` → `tool_call`（**仅上报**，执行在 Agent） | 执行仍在客户端 → 只能走扩展路径（register_tools + 网关 MCP） |
| 工具结果 | 客户端 → 网关 `type:"tool_result"` | `tool_call_update`（`status=completed`） | 方向变了：从"应答"变成"上报" |
| 权限 | 无 | `session/request_permission` + 四种 option kind | 新增 UI 与阻塞等待 |
| 取消 | 无 | `session/cancel`（通知）+ `stopReason:"cancelled"` | 需可中断的调用链 |
| 心跳/超时 | HTTP 超时（`tool.timeout-ms:60000`） | 传输层自定义（WS ping/pong 或 TCP 空闲超时） | 必须显式设计，否则半开连接会挂住会话 |

---

## 参考

- [ACP v1 Prompt Turn](https://agentclientprotocol.com/protocol/v1/prompt-turn)（`stopReason`、取消、`session/update` 变体）
- [ACP v1 Session Setup](https://agentclientprotocol.com/protocol/v1/session-setup)（`cwd` 绝对路径、`mcpServers` 可选）
- [ACP v1 Initialization](https://agentclientprotocol.com/protocol/v1/initialization)（能力省略即不支持）
- [ACP v1 Tool Calls](https://agentclientprotocol.com/protocol/v1/tool-calls)（`status`/`kind`/权限选项）
- [ACP v1 Transports](https://agentclientprotocol.com/protocol/v1/transports)（允许自定义传输）
