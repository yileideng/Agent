# Agent 生态讨论：完整要点与项目借鉴

> 来源：[DeepSeek 分享会话](https://chat.deepseek.com/share/axeeqqywn21wwpif7h)
> 原文：[2026-10-27-13-deepseek-share-transcript.md](./2026-10-27-13-deepseek-share-transcript.md)（52 条消息，保留用户消息、助手回复和思考；工具搜索结果未展开）
> 说明：会话由 AI 生成，引用的数据和结论需要二次验证；本文区分“会话观点”和“对本项目的建议”。
> **术语说明（2026-10 命名统一后补记）**：本文中的 "CAP" 指**本项目当时的客户端协议**，
> 现已统一命名为 **ACP**；本文中单独出现的 "ACP" 指 **Zed 的 ACP v1 规范**。

## 0. ACP 与 CAP：概念同源，实现不同

会话里的 **ACP = Agent Client Protocol**（即本文所称 ACP v1），由 Zed 主导，解决“客户端如何启动、连接、控制一个编码 Agent”。

它和本项目的关系，可以分成两层看：

- **概念层：是同一回事。** 都是“客户端作为宿主，Agent 作为能力插件，客户端负责 UI、权限、文件/工具，Agent 负责推理和工具调用”。ACP 的客户端并不只限编辑器，自定义 App、后端服务、移动端都可以做 ACP Client；本项目把它泛化到各种客户端，方向上是成立的。
- **实现层：当前不是同一个协议。** 本项目现在落地的是 **CAP**：HTTP/WS + `v/id/type/payload` 信封 + `register_tools/chat/tool_call/tool_result/final`；Zed ACP 是 **JSON-RPC + `initialize/session/new/prompt/session/update/request_permission`** 这一套。两者的方法、传输、生命周期和权限语义都不同。

所以更准确的说法是：

> **CAP 是“ACP 思路的泛化实现”，不是 Zed ACP 的线协议兼容实现。**

如果只是内部概念讨论，把 CAP 看作广义 ACP 没问题；如果要对外说“支持 ACP”，就需要实现/适配 Zed ACP 的 spec，否则熟悉 ACP 的人会误以为可以直接对接。

另外，ACP 这个缩写历史上还指代过 IBM 的 **Agent Communication Protocol**（已并入 A2A）和 OpenAI/Stripe 的 **Agentic Commerce Protocol**，文档里要写清是哪一个。

## 1. 讨论主线

这轮会话从“AI 怎么聊 QQ、刷抖音”开始，逐步深入到 26 个问题，核心是四条线：

1. 操作 App 的几种技术路径，以及纯 GUI Agent 为什么难落地；
2. DSH 的插件化、bridge 与 MCP 的区别；
3. Claude Code / Codex / ACP 的协议与适配器；
4. 客户端接入 Agent、MCP 网关、权限、移动端和 ACP Registry。

## 2. 核心结论

### 2.1 操作 App 有四条路径

| 路径 | 做法 | 适用 |
|---|---|---|
| GUI Agent | 截屏 → 视觉识别 → 鼠标/键盘 | 没有 API 的封闭软件；最通用但最慢、最脆弱 |
| API / MCP | 直接调平台或工具接口 | 有开放能力的平台；快、稳定、可审计 |
| 浏览器自动化 | Playwright / CDP 控制受控浏览器 | 网页版可控、但不想依赖官方 API |
| 手机 RPA / 无障碍 / root | 真机、云手机、系统层控制 | 移动端、需要真实设备行为 |

结论：**模拟鼠标只是其中一种，而且通常是最脆弱的一种。** 产品上应优先 API/MCP，封闭场景再考虑浏览器或 GUI。

### 2.2 Cradle 类纯 GUI Agent 的瓶颈

- **多步成功率坍塌**：单步动作准确率可以很高，端到端任务成功率却很低；
- **延迟和成本**：每步视觉推理，长任务时间和 token 成本难以承受；
- **数据与长尾**：高质量 GUI 标注昂贵，20% 异常分支维护成本指数上升；
- **生态反制**：平台用风控、SAEP 等限制外部自动化；
- **单位经济**：通用性越强，越难做到可靠、便宜、快。

对本项目：**不要把“万能桌面操作”作为主路径**。优先把业务动作封装成确定性工具，用 Agent 做编排和内容生成。

### 2.3 DSH 的插件化：Bridge 不等于 MCP

会话把 DSH 的做法概括为“一切皆插件”：

- QQ：`NapCat / OneBot` 协议网关，把消息桥接到 Agent；
- 抖音：浏览器自动化、接口、Android 底层控制等多条路径；
- 每个场景选最合适的专用通道，而不是执着于一种万能操作。

关键区分：

- **Bridge / Connector**：把平台私有协议翻译成 Agent 能消费的消息，例如 QQ → NapCat → Agent；
- **MCP**：标准化的工具调用接口，Agent 通过 `tools/list` / `tools/call` 使用工具。

**Bridge 不等于 MCP。** 一个平台没有 MCP，可以先写 bridge；有 MCP，就直接接 MCP。两者是并列方案，不是上下级。

### 2.4 ACP 的真实架构

会话对 ACP 的描述：

- **角色**：Client（编辑器/客户端）、Agent（编码智能体）、MCP Server（工具层）；
- **传输**：Client 启动 Agent 子进程，通过 **stdio + JSON-RPC 2.0** 双向通信；远程场景可用 HTTP/WebSocket，但会话认为远程支持仍在完善；
- **生命周期**：`initialize` → `session/new` / `session/load` → `prompt` → `session/update` 流式事件 → 权限请求 → `stop`；
- **双向请求**：Agent 可以反向请求 Client，例如 `session/request_permission`、`fs/read_text_file`；
- **MCP 不被替代**：ACP 管“Agent 住在哪、怎么和客户端交互”，MCP 管“Agent 用什么工具”。

核心价值：把编辑器和 Agent 之间的私有集成管道，变成标准的 N+M 接口；换 Agent 时客户端界面和工作流不变。

### 2.5 Adapter 不只是参数转换

会话对“Claude Code 调 Codex”的适配器拆解：

- **进程生命周期**：启动、维持、终止 Codex 进程；
- **会话/状态桥接**：映射 session/thread ID，转发上下文；
- **事件流**：把 Codex 的 `item/agentMessage/delta`、`turn/completed` 等事件转换成宿主能消费的事件；
- **权限回调**：Codex 请求修改文件/执行命令时，适配器把审批请求透传给客户端；
- **参数映射**：只是最末端、最可见的一步。

这对本项目非常关键：`AgentAdaptor` 本质就是一个**协议网关/适配器**，不是简单的 DTO 转换器。

### 2.6 Agent 的程序接口并不统一

会话列举了多种形态：

- CLI headless：Claude Code `-p`、Cursor `agent -p`、Codex `codex exec`；
- 底层 JSON-RPC：Codex `app-server`、DSH SDK、ACP stdio；
- 平台 HTTP API；
- 完全无接口，只能 GUI 自动化；
- 移动端无法直接 stdio，需要 WebSocket 桥接。

所以 Adaptor 不能假设“所有 Agent 都是同一种接口”，需要按 Agent 类型做 profile。

### 2.7 移动端 + 云端 + MCP 的正确分层

会话对“小红书 App 接入 Codex”的结论：

- 手机无法 spawn 本地 Codex 进程，stdio 不可用；
- 合理链路：手机 App（WS Client）↔ 云端桥接网关（WS ⇄ stdio）↔ 云端 Codex；
- 云端 Codex 看不到手机相册/本地文件，所以“剪裁图片”必须通过 **MCP 工具回调**回手机执行；
- 网关可以承接 MCP Server 的**协议层**，但承接不了**执行面**；如果只声明工具、不打通到 App 的执行通道，就会变成“有声明、无实现”的空壳；
- 权限弹窗必须出现在 App 上，由用户确认。

这与本项目“网关承接 MCP Server + 客户端执行工具”的思路高度一致，也说明 **MCP 协议层和执行层必须分开**。

### 2.8 权限：交互面 vs 强制面

会话讨论了一个很关键的区分：

- **ACP 原生权限请求**：框架强制，Agent 不能绕过，因为执行入口在 Client；
- **把权限确认做成 MCP 工具**：只是 Agent 主动调用的普通工具，Agent 可能忘记、顺序错、复用授权；
- 因此：
  - **交互面**可以做成 MCP 工具（`request_permission`），让用户弹窗；
  - **强制面**必须落在网关/执行侧：工具级审批 + permission token（绑定工具名和参数哈希、一次性、短超时）+ App 最终校验；
  - 工具内部拦截可以作为最后一道防线，但不能作为唯一机制。

对本项目：`tool_call` 的风险等级、用户确认、token/幂等、客户端最终校验，应该按这个分层设计，而不是只加一个“确认工具”。

### 2.9 ACP Registry 的核心思路

会话总结 ACP Registry：

- **不托管 Agent 二进制**，只托管“如何找到并启动 Agent”的 manifest；
- manifest 包含：身份、能力声明、分发方式、启动命令、协议版本；
- 中心化索引 + 去中心化执行；
- 不解决信任与安全，安装 Agent 仍然是“信任厂商”。

对本项目：`/agents` 目前只是能力发现；如果未来做 Agent 市场/接入目录，可以参考 manifest 的字段设计，但不必现在引入完整 Registry。

### 2.10 “统一 ACP”为什么难

会话的判断：

- 原生支持 ACP 的：Gemini CLI、Cursor、GitHub Copilot CLI 等；
- Claude Code、Codex 通过 `claude-agent-acp` / `codex-acp` 适配器接入；
- Anthropic 仍以 MCP 为主要集成标准，未原生拥抱 ACP；
- 难点：编辑器宿主 vs Agent 宿主的控制权、ACP 命名混淆、安全责任归属、适配器“二等公民”体验；
- 更可能的终局是**分层格局**，而不是所有厂商原生说同一种语言。

对本项目：**不要假设所有 Agent 都会原生说同一种协议**；CAP 保持稳定，Adaptor/Bridge 负责差异，ACP 只在需要接编辑器生态时作为可选适配。

## 3. 对本项目最值得借鉴的 8 条

1. **命名要分层**：概念上 CAP 可以作为 ACP 的泛化实现；对外则要区分 CAP（当前实现）与 ACP（Zed spec），不要把 CAP 说成 ACP 兼容。
2. **工具面优先 MCP，但保留 Bridge**：有 MCP 用 MCP；没有 MCP 的平台写 bridge，不要把它们混为一谈。
3. **Adaptor 是协议网关**：进程、会话、事件流、权限、参数映射都要考虑，不能只做 DTO 转换。
4. **MCP 协议层和执行层分离**：网关可以承接 MCP Server，但工具必须在客户端执行；必须有回传执行结果的通道。
5. **权限分层**：交互在客户端/工具，强制在网关/执行侧；token 绑定 + 一次性 + 超时 + 最终校验。
6. **移动端用 WS 桥接**：手机无法 stdio，云端网关 + WS + 本地 MCP 工具回调是合理架构。
7. **Agent 接口异构**：CLI headless、app-server JSON-RPC、HTTP、SDK 并存；Adaptor 需要 profile，而不是一个万能命令。
8. **垂直可靠性优先**：纯 GUI 通用自动化不适合作为主路径；垂直工具 + 评测 + 单位经济更现实。

## 4. 不建议照搬

- 不要把纯视觉 GUI Agent 当作主路径；
- 不要试图定义“让所有 Agent 厂商都采用”的万能协议；
- 不要把 bridge 叫成 MCP，也不要把 CAP 说成“Zed ACP 兼容”；可以说它是 ACP 思路的泛化；
- 不要用 MCP 工具替代权限强制面；
- 不要忽略平台风控、授权、用户知情和平台条款；
- 不要为了“统一”把 UI、工具、协作、运行时塞进同一个协议。

## 5. 可落地的下一步

- 对外文档：当前实现叫 **CAP**；若称 ACP，必须注明是“泛化 profile”，不是 Zed ACP spec 兼容；需要兼容时再加 ACP 适配器；
- 在 `AgentAdaptor` 文档中补“协议网关”职责：进程、会话、事件、权限、参数；
- 评估是否需要支持 ACP Agent（作为可选适配器），而不是把 CAP 改成 ACP；
- 设计工具权限：`riskLevel` + `request_permission` 交互 + 网关 token 校验 + 客户端最终校验；
- 移动端继续走“网关 MCP + WS 回传执行”的架构；
- `/agents` 未来可演进为 manifest 式注册表（能力、协议版本、启动方式），当前不急着做。

## 6. 参考

- 会话原文：[2026-10-27-13-deepseek-share-transcript.md](./2026-10-27-13-deepseek-share-transcript.md)
- 会话中提到的外部线索（未逐条核验）：
  - Cradle：https://icml.cc/virtual/2025/poster/46393
  - DSH 插件化：https://eu.36kr.com/zh/p/3947852851664512
  - QQ bridge / OneBot / MCP：https://lobehub.com/mcp/huliangwei-qq-onebot-mcp
  - MCP / A2A / 传输层：https://venturebeat.com/orchestration/mcp-solved-tool-calling-a2a-solved-coordination-what-solves-transport
