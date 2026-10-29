# wxDemo 只读工具接入 Agent：技术方案

> 目标：把 `D:\Git-repository\wx-demo` 作为 ACP 客户端接入 Agent Gateway，先跑通一个只读工具闭环
> 范围：聊天面板 + `get_circuit` 工具 + HTTP 回调，不修改画布
> 状态：方案，按阶段实现

## 1. 目标闭环

```text
wxDemo 启动
  → 向 Gateway 注册 get_circuit 工具
  → 用户在 AI 面板输入问题
  → Gateway 选择 Agent（先用 cli）
  → Agent 调用 get_circuit
  → Gateway 回调 wxDemo 本地 HTTP 服务
  → wxDemo 返回当前电路 JSON
  → Agent 解释电路
  → 面板显示回答
```

验收标准：

- 能注册成功；
- Agent 能调用 `get_circuit`；
- 返回的 JSON 能被 Agent 正确理解；
- 画布不被修改；
- 崩溃/超时有明确错误提示。

## 2. 为什么用 HTTP 回调，不用 WS

第一版选 **SERVER 客户端 + HTTP 回调**：

```text
wxDemo  --HTTP-->  Gateway  /clients/register、/chat
wxDemo  <--HTTP--  Gateway  /client/tools/execute
```

原因：

- wxWidgets 没有内置 WebSocket 客户端；
- 桌面程序开一个 localhost 回调端口比实现 WS 客户端简单；
- 只读工具不需要双向长连接；
- 方便先用 `wxWebRequest` 做 HTTP 客户端，用最小 Winsock HTTP Server 做本地回调服务。

代价：

- 需要一个本地端口（建议 `127.0.0.1:18080`）；
- Windows 防火墙可能首次弹窗；
- 生产环境要考虑端口冲突和鉴权。

WS 留到后续移动端/无入站端口场景。

## 3. 组件划分

```text
AgentConfig.h         Gateway 地址、clientId、端口、agent 名称
AgentClient.h/.cpp    wxWebRequest 注册/聊天
AgentPanel.h/.cpp     AI 聊天面板
AgentToolServer.h/.cpp 本地 HTTP 回调 + 工具分发
AgentTools.h/.cpp     get_circuit 等工具实现
DrawBoard 扩展        ToJson()，只读导出
```

## 4. 协议

### 4.1 注册工具

```http
POST http://127.0.0.1:8080/clients/register
Content-Type: application/json
```

```json
{
  "v": "0.1",
  "id": "reg-1",
  "type": "register_tools",
  "payload": {
    "clientId": "wx-demo",
    "clientType": "SERVER",
    "baseCallbackUrl": "http://127.0.0.1:18080",
    "defaultCallbackPath": "/client/tools/execute",
    "clientPrompt": "你是电路设计助手，先读取当前电路再回答。",
    "tools": [
      {
        "name": "get_circuit",
        "description": "获取当前画布上的所有元件和引脚，返回 JSON",
        "inputSchema": {
          "type": "object",
          "properties": {},
          "additionalProperties": false
        }
      }
    ]
  }
}
```

### 4.2 发起对话

```http
POST http://127.0.0.1:8080/chat
Content-Type: application/json
Accept: application/json
```

```json
{
  "v": "0.1",
  "id": "chat-1",
  "type": "chat",
  "payload": {
    "clientId": "wx-demo",
    "endUserId": "demo-user",
    "prompt": "解释一下当前画布上的电路",
    "agent": "cli"
  }
}
```

第一版用同步 `final`，不做 SSE。

### 4.3 工具回调

Gateway 发到：

```http
POST http://127.0.0.1:18080/client/tools/execute
```

```json
{
  "v": "0.1",
  "id": "call-1",
  "type": "tool_call",
  "payload": {
    "clientId": "wx-demo",
    "endUserId": "demo-user",
    "sessionId": "sess-1",
    "chatId": "chat-1",
    "callId": "call-1",
    "tool": "get_circuit",
    "arguments": {},
    "deadlineMs": 5000
  }
}
```

wxDemo 返回：

```json
{
  "v": "0.1",
  "id": "call-1",
  "type": "tool_result",
  "payload": {
    "chatId": "chat-1",
    "callId": "call-1",
    "status": "success",
    "result": "{\"count\":2,\"shapes\":[...]}"
  }
}
```

失败时 `status:"error"`，`error.message` 写原因。

## 5. get_circuit 设计

`DrawBoard` 目前 `shapes` 是私有的，`SaveAsJSON()` 会弹文件对话框，不能直接给工具用。

新增：

```cpp
// DrawBoard.h
json ToJson() const;
std::string ToJsonString() const;
```

返回：

```json
{
  "count": 2,
  "shapes": [
    {
      "type": "AndGate",
      "centerPos": [200, 200],
      "inputPin": ["Low", "Low"],
      "outputPin": "Low"
    }
  ]
}
```

工具只读，不调用 `AddShape` / `RemoveShape` / `Undo`。

## 6. 线程模型

关键点：**HTTP 回调线程不能直接操作 wxWidgets 控件。**

```text
本地 HTTP 工作线程
  → get_circuit
  → wxTheApp->CallAfter(...)
  → 主线程读取 DrawBoard
  → promise 返回 JSON
  → 工作线程返回 HTTP 响应
```

`get_circuit` 是只读，等待时间应很短；设置 2 秒超时，超时返回 `tool_result.error`。

## 7. AgentClient

使用 wxWidgets 自带 `wxWebRequest`：

- `RegisterTools()`：启动后 POST `/clients/register`；
- `Chat(prompt)`：POST `/chat`；
- 绑定 `wxEVT_WEBREQUEST_STATE`，完成后解析 `final` 或 `error`；
- 第一版只处理同步最终结果，不做逐字流式。

为了避免主线程卡死，所有网络请求必须异步。

## 8. AgentToolServer

使用最小 Winsock HTTP Server（不引入第三方库）：

```cpp
// 只实现 POST /client/tools/execute
// 读取 header + Content-Length body
// 解析 tool_call
// 分发到 AgentTools
// 返回 tool_result
// bind 127.0.0.1:18080
```

工具分发：

```text
tool == "get_circuit" -> AgentTools::GetCircuit()
```

未知工具返回 `tool_result.error`，不要 500。

## 9. UI

在 `MyFrame1` 右侧增加 `AgentPanel`：

- 多行只读输出框；
- 单行输入框；
- “发送”按钮；
- 状态栏：未连接 / 已注册 / 请求中 / 失败。

第一版不做会话列表、历史记录、设置页。

## 10. 改造步骤

1. `DrawBoard::ToJsonString()`；
2. `AgentConfig.h`；
3. `AgentTools::GetCircuit()`；
4. `AgentToolServer`（Winsock + tool_result）；
5. `AgentClient`（wxWebRequest 注册 + chat）；
6. `AgentPanel`（UI + 状态）；
7. 挂到 `MyFrame1`；
8. 更新 `wxDemo.vcxproj`（新增源文件、链接 ws2_32.lib）；
9. 启动 Gateway + `cli` Agent，编译运行验证。

## 11. 风险

- 最小 HTTP Server 需要 Winsock，工程要链接 `ws2_32.lib`；
- 本地 18080 被占用时要能提示；
- `wxWebRequest` 事件必须在主线程处理；
- `get_circuit` 返回大 JSON 时注意长度；
- Gateway 未启动时注册失败，需要重试或手动重连；
- `cli` Agent 已验证支持客户端 MCP 工具；`qwen-acp` 暂不适合做工具链路验证。

## 12. 后续

- 增加 `export_png`（只导出，不改电路）；
- 增加 `add_component` / `set_input_pin`；
- 再考虑 SSE 流式；

## 13. 实现状态（2026-10-27 晚）

代码已落地，客户端改动在 `D:\Git-repository\wx-demo`：

| 位置 | 内容 |
| --- | --- |
| `AgentConfig.h` | 网关地址、clientId、回调端口 |
| `AgentClient.*` | `wxWebRequest` 异步注册 / 对话 |
| `AgentToolServer.*` | 最小 Winsock HTTP 回调服务（127.0.0.1:18080） |
| `AgentTools.*` | `get_circuit` 分发 |
| `AgentPanel.*` | AI 面板 + 状态提示 |
| `DrawBoard::ToJson()` | 只读导出当前电路 |
| `wxWidgets.props` | 链接 `ws2_32.lib` |

本篇已修的问题：

1. **回调超时口径**：原来固定 2 秒，改为按 Gateway 下发的 `deadlineMs`（上限 5 秒），避免客户端先超时而丢掉已算出的结果；
2. **future 顺序**：`get_future()` 提前到 `CallAfter` 投递之前，消除与主线程 `set_value` 的竞争；
3. **迟到回调崩溃**：主线程超时返回后 `set_value` 会抛 `future_error`，现已吞掉；主线程执行体的异常也统一包成 `tool_result.error`；
4. **winsock 头顺序**：`demo.h` 顶部先引入 `<winsock2.h>`，避免被 `<windows.h>` 抢先拉入 `winsock.h` 造成 `ws2_32.lib` 符号重定义；
5. **销毁顺序**：`MyFrame1` 析构先删 `agentPanel`（停回调线程、断开网络回调），再删工具栏；
6. **连接健壮性**：accept 后设置收发超时、请求体大小上限、bind 失败带上错误码。

验证情况：

- `AgentTools.cpp` 用 MinGW g++ 以占位头单独编译并跑了错误分支用例，全部通过；
- `mvn clean package`（网关侧）此前已通过，网关侧本次未改动；
- **wxDemo 本体未在本机重新编译**：本机没有可用的 MSVC/MSBuild（VS2022 未安装，VS2019 目录下无工具链），wxWidgets SDK 是 vc14x 版本无法用 MinGW 链接。需要用户在 VS 2022 里“重新生成解决方案”后再做端到端联调。

端到端联调步骤见客户端仓库 `README.md#ai-助手（只读工具闭环）`。

## 14. 端到端跑通后的问题与修复（2026-10-27 晚）

联调截图确认闭环工作正常：面板提问 → Agent 调用 `get_circuit` → 返回元件与引脚电平。
但暴露出两个显示层问题。

### 14.1 中文乱码：窄字面量被按 locale 解码

现象：面板里写死的文案（标题、状态、"你:"）全是乱码，而 Agent 返回的中文正常。
后者正常、前者乱码这个差异正好定位了原因 —— 后者走 `wxString::FromUTF8`，前者是窄字面量。

```cpp
// ✗ 工程用 /utf-8，窄字面量是 UTF-8 字节；wxString 构造却按系统 locale(GBK) 解码
new wxStaticText(this, wxID_ANY, "AI 助手");

// ✓ wxT() 在 Unicode 构建下是 L""，编译期就成为 UTF-16，不经过 locale 转换
new wxStaticText(this, wxID_ANY, wxT("AI 助手"));
```

统一的规则：

| 场景 | 写法 |
| --- | --- |
| 界面文案（`wxString`） | `wxT("中文")` |
| 发给 Gateway 的 JSON | `Utf8(wxT("中文"))`，先 UTF-16 再转 UTF-8 字节 |
| 交给 Markdown 解析 | `wxString::FromUTF8(...)` |
| 纯 `std::string` 错误信息 | 可直接用窄字面量（`/utf-8` 下即 UTF-8） |

### 14.2 排版混乱：Markdown 被当纯文本显示

Agent 返回 `**加粗**`、`1.` 列表、``` 代码块，面板原样输出，星号和编号都露在字面上。
新增 `MarkdownLite`（纯标准库，不依赖 wx）把 Markdown 解析成块/片段，再逐段套样式：

| Markdown | 渲染 |
| --- | --- |
| `# 标题` | 加粗、字号 +1 |
| `**粗**` / `*斜*` / `` `码` `` | 对应字重/样式，行内代码带浅灰底 |
| `1.` / `-` | 保留编号、按层级缩进，连续列表项紧排 |
| ``` 代码块 | 等宽字体 + 浅灰底 |
| 表格行 | 去竖线，换空格分列 |

样式用 `wxTextCtrl::SetStyle(start, end, attr)` 按区间施加（`wxTE_RICH2`），
比依赖 `SetDefaultStyle` 更可靠。面板同时补了 header 行、带颜色的状态、输入行布局和最小宽度。

### 14.3 顺带修掉的一个隐蔽问题

`AgentToolServer.cpp` 里 `<winsock2.h>` 原先排在 `DrawBoard.h` 之后，而 `DrawBoard.h` 会拉入
`<wx/wx.h>` → `<windows.h>` → `winsock.h`(1.1)，MSVC 下会因类型重定义编译失败。已把
winsock 头提到文件最前。

### 14.4 本轮验证

- `MarkdownLite` 写了 10 组单元测试（标题、带粗体的有序列表、嵌套列表、代码块、行内代码、
  链接、表格、CRLF、纯文本降级、软换行合并），用 MinGW g++ 编译运行 **全部通过**；
- 用 SDK 里真实的 wx 配置头（`lib/vc14x_x64_dll/mswu`）对 `AgentPanel.cpp`、`AgentClient.cpp`、
  `AgentToolServer.cpp`、`AgentTools.cpp`、`MarkdownLite.cpp`、`demo.cpp` 做 `-fsyntax-only` 检查，
  全部通过 —— 覆盖了 wx API 签名与类型匹配；
- 仍受限于本机没有 MSVC，**未做真正的链接与运行**，需要用户在 VS 2022 重新生成后确认显示效果。

## 15. 让 Agent 真正读懂电路（2026-10-27 深夜）

跑通后发现更深的问题：**工具返回的是"几何图元清单"，不是"电路描述"。**

截图里的实证：画布上只有一个矩形和两个输入引脚，Agent 却回答「矩形是一个连接线或导线」。
它不是读出来的，是照着 `type` 和坐标猜的。

### 15.1 缺的是什么

| 缺失项 | 后果 |
| --- | --- |
| 引脚坐标 | `PinPoint` 里本来就有 `pos`，但存盘格式没导出，模型不知道门的引脚在哪 |
| 连接关系 | 只有坐标，模型必须自己比对数字判断谁连谁，引脚一多必错 |
| 图元角色 | 分不清「电路元件」和「只是画着看的矩形/圆」 |
| 坐标系/画布尺寸 | 甚至无法判断两个图元是否相邻 |
| `thickness` | 纯渲染参数，反而是噪音 |

### 15.2 方案：几何交给程序，结论交给模型

原则是**不要让模型做几何计算**。新增两层：

1. `DrawBoard::ToAgentJson()` —— 面向 Agent 的视图，补 `role`、`id`、门的引脚坐标、
   画布尺寸与坐标系说明，并去掉 `thickness`。
   **刻意不复用 `ToJSON()`**：那是存盘格式，`FromJSON` 依赖它的字段，改它会破坏已保存的画布文件。
2. `CircuitReport` —— 纯标准库模块，做两件事：
   - `BuildConnections()`：导线端点 ↔ 引脚坐标的容差匹配（6px），也支持导线接导线；
   - `BuildSummary()`：生成一句中文结论，模型先读它就够。

最终返回：

```json
{
  "canvas": {"width": 800, "height": 600, "note": "像素坐标，原点在画布左上角…"},
  "count": 3,
  "shapes": [{"id": "InputPin-1", "type": "InputPin", "role": "component", ...}],
  "connections": [{"kind": "wire-pin", "wire": "Line-1", "wireEnd": "a",
                   "pin": {"shape": "AndGate-1", "port": "in0"}, "distancePx": 0}],
  "summary": "画布 800×600 像素。共 5 个图元：输入引脚×1，与门×1，输出引脚×1，导线×2。检测到 4 处连接。包含 1 个逻辑门。"
}
```

`role` 是解决误判的关键：`component` / `wire` / `drawing` 三值，
模型据此知道矩形只是绘图元素。同时给 `get_circuit` 的 `description` 补上了字段语义说明。

### 15.3 顺带修掉 NotGate 的格式不一致

`NotGate::ToJSON()` 直接把枚举塞进 json，输出 `0/1/2`，而其它门是 `"High"/"Low"/"Off"`。
更危险的是工程里存在两套枚举（`PinValue` 是 `Off=0,Low=1,High=2`，`Pin_Value` 是
`High=0,Low=1,Off=2`），一旦混用会静默出错。已统一走 `PinValueToString()`。

### 15.4 验证

- `CircuitReport` 写了 8 组、共 21 条断言：空画布、导线接双引脚、容差内/外（5.6px 命中、14px 不命中）、
  导线接导线、多引脚取最近、含绘图元素的摘要、无逻辑门摘要、连接计数。
  用 MinGW g++ 编译运行 **全部通过**；
- 另外跑了一个样例程序，打印真实返回的 JSON 人工核对，确认矩形被标为 `drawing`、
  摘要明确写出「未检测到任何导线把元件连接起来」；
- 9 个源文件用真实 wx 配置头 `-fsyntax-only` 全部通过；
- 仍未做 MSVC 真编译，需要用户重新生成后联调。
