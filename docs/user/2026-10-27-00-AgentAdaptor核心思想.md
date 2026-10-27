# AgentAdaptor 核心思想

> 本文档汇总项目的重要设计思想，不是单次迭代的功能清单。
>
> 维护约定：**已有设计思想只追加、不覆盖**；新的思想放到对应主题下；如果旧结论需要修正，用“补充/修正”标注。

## 一、总体模型

### 1.1 三个协议，三个“插座”

| 协议 | 连接双方 | 解决的问题 |
|---|---|---|
| **CAP** | Client ↔ Gateway | 客户端怎么统一接入 |
| **MCP** | Agent ↔ Tool | 工具从哪里来 |
| **ACP** | Gateway/Client ↔ Agent | Agent 怎么统一接入 |

### 1.2 Gateway 只做四件事

1. **客户端入口**：CAP 解析、SSE、工具回调；
2. **Agent 注册与路由**：Registry 管“有哪些”，Router 管“选哪个”；
3. **MCP Server**：把客户端工具暴露给 Agent；
4. **ACP Client**：连接内置 Agent 或外部 ACP Server。

不负责：

- Agent 的推理逻辑；
- Agent 内部上下文；
- 外部 Agent 的部署和进程管理。

### 1.3 不变量

- 客户端只面对 CAP，不感知 Agent 厂商；
- Agent 侧保持异构，Gateway 负责协议翻译；
- 工具执行留在客户端，Gateway 只做路由和转换。

## 二、客户端工具：CAP ↔ MCP 翻译

### 2.1 机制

- 客户端只做一件事：把自己的工具（`name/description/inputSchema`）注册给网关；
- 网关对外暴露 MCP Server，Agent 看到的是标准 MCP 工具；
- Agent 调 `tools/call`，网关再翻译成 CAP `tool_call`，通过 HTTP 回调/WS 让客户端执行；
- 客户端不需要实现 MCP，Agent 不需要知道客户端的存在。

### 2.2 核心不变量

**MCP 是 Agent 侧的通用插座，CAP 是客户端侧的通用插座，Adaptor/网关做中间翻译。**

## 三、流式输出：异构事件 → 统一事件

### 3.1 机制

- CLI 是 `stream-json`，Coze 是厂商 SSE，Self Brain 是 HTTP；
- Adaptor 把它们统一成 `delta / thinking / finish / error`；
- 网关再统一成 CAP SSE 的 `delta / thinking / final / error`；
- 客户端只认一套协议，厂商事件永远不泄漏。

### 3.2 核心不变量

**Agent 侧保持异构，Adaptor 和客户端侧保持协议统一。**

## 四、Agent 接入：ACP Server 外置

### 4.1 ACP Server 对标 MCP Server

MCP 把工具实现外置：

> 工具方实现 MCP Server，Agent 用 MCP Client 去接，平台不需要实现每个工具。

ACP 把 Agent 接入外置：

> Agent 方实现 ACP Server，Gateway 用 ACP Client 去接，系统不需要实现每个 Agent 的私有协议。

### 4.2 适配责任外置

传统做法是：Gateway 每接一个 Agent，就为它写一个 Adaptor。Agent 越多，Gateway 里的私有协议适配越多。

ACP Server 改变了这个分工：

- 不是“不做适配”，而是把 Agent 侧的 ACP 适配责任外包给外部服务；
- ACP Server 不是新协议，而是“实现 ACP 协议的外部 Agent 服务”；
- 对 Gateway 来说，它就是一个可注册的 Agent endpoint。

### 4.3 两种 Agent 来源

| 类型 | 谁负责协议适配 | Gateway 做什么 |
|---|---|---|
| 内置 Agent（Self/Coze/CLI） | Gateway 内部 Adaptor | 直接调用 |
| 外部 ACP Server | Agent 厂商/外部服务 | 只连接 `wsUrl`、注册、路由 |
| 本地只有 stdio 的 Agent | 本地 Connector 或适配服务 | 对外暴露 WS，再按外部 ACP Server 接入 |

### 4.4 外部注册的边界

- 外部注册只给 `wsUrl`，不给 `command`；
- 外部 Agent 的进程由外部服务自己管理；
- Gateway 不在本地替外部 Agent 启动进程；
- 本地 Agent 想走外部接入，应由本地 Connector 包成 WS ACP Server。

### 4.5 实现验证

- 接口从 `AgentAdaptor` 收敛为 `AcpAgent`；
- 新增 `AgentRouter`，Controller 不再直接查 Registry；
- `AgentRegistry` 同时管理内置 Agent 和外部注册 Agent；
- ACP 连接分两种：
  - `StdioAcpConnection`：内置/本地 Agent；
  - `WsAcpConnection`：外部 ACP Server。
- 外部注册只接受 `wsUrl`；
- `AcpAgentServer` 把 `qwen --acp` 包成 WS ACP Server，验证了“外部 Agent 自己实现 ACP 适配”这条路径。

这些是实现结果，用来印证前面的分层，不是本文档的主线。

### 4.6 边界

- ACP Server 是外部 Agent 运行时，不是 Gateway 的子模块；
- WS 是 ACP 的自定义传输，公网要考虑 TLS、重连、健康检查；
- 外部注册当前是内存态、无管理员鉴权，只适合 demo；
- MCP 工具和 ACP Agent 接入是两条独立链路；
- 内置的非 ACP Agent 仍然需要 Gateway 内部 Adaptor。

## 五、一句话

**MCP 把工具实现外包给 MCP Server；ACP 把 Agent 接入外包给 ACP Server；Gateway 只保留Agent注册、客户端注册、路由和协议翻译。**

## 六、维护约定

- 本文档只收**重要设计思想**，不写具体实现细节和单次迭代清单；
- 已有条目是历史结论，不覆盖、不删除；
- 新思想放到对应主题下，必要时注明“补充”或“修正”；
- 如果新实现推翻了旧结论，保留旧结论并说明原因，而不是直接替换。
