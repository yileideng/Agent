# AgentAdaptor 核心思想

## 1. 客户端工具：把客户端工具翻译成 MCP 工具

- 客户端只做一件事：把自己的工具（`name/description/inputSchema`）注册给网关；
- 网关对外暴露 MCP Server，Agent 看到的是标准 MCP 工具；
- Agent 调 `tools/call`，网关再翻译成 CAP `tool_call`，通过 HTTP 回调/WS 让客户端执行；
- 客户端不需要实现 MCP，Agent 不需要知道客户端的存在。

这是最核心的创新点：**MCP 是 Agent 侧的通用插座，CAP 是客户端侧的通用插座，Adaptor/网关做中间翻译。**

## 2. 流式输出客户端协议：把 Agent 的异构流翻译成统一事件

- CLI 是 `stream-json`，Coze 是厂商 SSE，Self Brain 是 HTTP；
- Adaptor 把它们统一成 `delta / thinking / finish / error`；
- 网关再统一成 CAP SSE 的 `delta / thinking / final / error`；
- 客户端只认一套协议，厂商事件永远不泄漏。

本质是：**Agent 侧保持异构，Adaptor 和客户端侧保持协议统一。**

## 后续新增要点

- 只写机制，不写状态、边界、路线图；
- 一句话说清“谁把什么翻译成什么”；
- 保留角色、方向、转换和不变量；
- 优先挂到已有两个面下，只有新轴线才新开一节。
