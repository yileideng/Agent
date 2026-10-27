# wxDemo 接入 AgentGateway：完整接入方案

> 状态：方案，待评审
> 前置：只读闭环已跑通并验证，见 [wxDemo 只读接入方案](./2026-10-27-18-wx-demo-readonly-agent-plan.md)
> 目标读者：本项目开发者

## 1. 目标

把 wxDemo 从「能问一句当前电路」推进到「能辅助设计电路」的完整 CAP 客户端：

1. **反馈及时**：回答边生成边显示，不是等整段；
2. **会话可用**：能开新对话、能看懂当前连接状态；
3. **读得深入**：除了元件清单，还能给出网络（net）级结构；
4. **改得安全**：能受控修改电路，且随时可撤销；
5. **可作参考**：成为其他客户端接入 CAP 的样板。

## 2. 现状盘点

### 2.1 已具备

| 能力 | 实现位置 | 状态 |
| --- | --- | --- |
| 工具注册 / 覆盖 | `AgentClient::RegisterTools` | ✅ |
| 本地 HTTP 回调 | `AgentToolServer` | ✅ |
| 只读工具 `get_circuit` | `AgentTools` | ✅ |
| 画布语义化报告 | `DrawBoard::ToAgentJson` + `CircuitReport` | ✅ |
| 连接推断（6px 容差） | `CircuitReport::BuildConnections` | ✅ |
| 多轮续聊 | `AgentClient::sessionId_` 回传 | ✅ |
| Markdown 富文本渲染 | `MarkdownLite` + `wxTextCtrl::SetStyle` | ✅ |
| 请求入参日志（网关侧） | `HttpRequestLoggingFilter` | ✅ |

### 2.2 差距

| 差距 | 影响 | 优先级 |
| --- | --- | --- |
| 只处理同步 `final` | 长回答时面板长时间空白，像卡死 | P0 |
| 没有会话管理入口 | 无法开新对话，`sessionId` 只能一路累积 | P0 |
| 注册失败不重试 | 网关比客户端晚启动就得重启客户端 | P0 |
| 工具执行期间无反馈 | Agent 调 `get_circuit` 时界面无提示 | P1 |
| 画布文字标注未导出 | 用户用 Text Tool 写的注释 Agent 看不到 | P1 |
| 无网络（net）级结构 | 只能说「A 连到 B」，说不清「这几根线是同一个网络」 | P1 |
| 无导出能力 | Agent 不能出图 | P2 |
| 无写操作 | Agent 只能看，不能改引脚、不能加元件 | P2 |

### 2.3 网关侧能力边界（已确认）

| 能力 | 结论 |
| --- | --- |
| SSE 流式 | 支持，`Accept: text/event-stream` 走 `SseEmitter`，发 `delta`/`thinking`/`final`/`error` |
| 会话续接 | `sessionId` 由客户端回传，网关按 `(adaptor, clientId, endUserId, sessionId)` 隔离 |
| 客户端工具 | 通过 `/mcp/{clientId}` 暴露给 Agent，CLI 只能看到自己客户端的工具 |
| 单轮多次工具调用 | 支持，`self` 适配器 `max-steps` 默认 5；CLI 由 Agent 自身决定 |

## 3. 设计原则

1. **模型不做计算**：几何、拓扑、连通性一律客户端算好，模型只读结论。
2. **写操作必须可撤销**：任何改动画布的工具都要进 Undo 栈，且向用户明示。
3. **存盘格式与 Agent 视图分离**：`ToJSON`/`FromJSON` 是文件格式，Agent 视图走 `ToAgentJson`。
4. **能力可降级**：SSE 不可用时自动回退同步模式，功能不丢。
5. **每阶段独立可验收**：不把三期改动混在一次发布里。

## 4. 分层架构

```text
┌─────────────────────────────────────────────┐
│ UI 层    AgentPanel                         │
│          流式渲染 / 会话切换 / 状态可视化      │
├─────────────────────────────────────────────┤
│ 工具层   AgentTools                         │
│          工具分发、参数校验、写操作权限        │
├─────────────────────────────────────────────┤
│ 语义层   CircuitReport（连接、网络、摘要）     │
│          MarkdownLite（渲染结构）             │
├─────────────────────────────────────────────┤
│ 传输层   AgentClient（注册 / chat / SSE）     │
│          AgentToolServer（HTTP 回调）         │
├─────────────────────────────────────────────┤
│ 画布层   DrawBoard                          │
│          ToAgentJson（读）/ 写操作 API        │
└─────────────────────────────────────────────┘
```

语义层与传输层都不依赖 wxWidgets，可脱离 GUI 做单元测试 —— 这是当前代码已经遵守的约定，后续继续保持。

## 5. 阶段计划

### 阶段 1：流式与稳定性（P0）

#### 1.1 SSE 流式输出

当前 `Chat()` 只在 `State_Completed` 时读整个响应体，中间过程完全静默。

实现要点：

```cpp
request_.SetHeader("Accept", "text/event-stream");
request_.SetStorage(wxWebRequest::Storage_None);   // 必须，否则不触发 data 事件
// 绑定 wxEVT_WEBREQUEST_DATA，累积 buffer，按 "\n\n" 切帧
```

帧格式（**实测**，`curl -N -H "Accept: text/event-stream"` 打真实网关得到）：

```text
event:delta
data:{"v":"0.1","id":"sse-probe-1","type":"delta","payload":{"chatId":"72c58b58-...","seq":1,"text":"你好"}}

event:final
data:{"v":"0.1","id":"sse-probe-1","type":"final","payload":{"sessionId":"wx-demo:demo-user:fbc90b10-...","chatId":"72c58b58-...","agent":"cli","output":"你好"}}
```

确认的几点：

- 帧以空行 `\n\n` 分隔，每帧含 `event:` 行 + `data:` 行；
- `data` 里的 `payload.seq` 单调递增，可用于去重/排序；
- `final` 帧带 `sessionId`，流式模式下同样要把它存下来；
- 帧里 `event:` 名与 `data.type` 一致；客户端以 `data.type` 为准，`event:` 只当提示。


#### 1.1.1 实测数据与预期修正

上述探测（`agent=cli`，回答只有两个字「你好」）：

| 指标 | 实测 |
| --- | --- |
| 从发请求到收到第一个 `delta` | **约 5.6 秒** |
| 总 delta 数 | 1 |
| `thinking` 帧 | 未出现（CLI 未产生） |

**结论：SSE 不会缩短首字延迟。** 那 5.6 秒几乎全耗在 CLI 进程启动与 Qwen 首包上，与是否流式无关。
流式的真正收益是**长回答时正文持续增长**，而不是「更快看到第一个字」。

因此验收标准必须是：

- 长回答（比如让它解释整个电路）时，正文分段出现，而不是最后一次性刷出；
- 首字延迟与同步模式基本持平（约 5 秒），**不要求**低于 1 秒；
- `thinking` 是否出现取决于 Agent，客户端要做兼容但不对它做承诺。

如果只是想让用户「感觉没卡死」，还有一个更便宜的做法：请求发出后立即在面板显示计时器（「已等待 3s…」）。
这个可以和 SSE 一起做，也可以单独先做。

处理规则：

| 信封 | 处理 |
| --- | --- |
| `delta` | 追加到面板当前气泡 |
| `thinking` | 追加但用灰色斜体，和正文区分 |
| `final` | 收尾，保存 `sessionId`，把累积文本按 Markdown 重绘一次 |
| `error` | 显示错误，结束本轮 |

**降级策略**：若服务端返回非 200 或首帧超时（3s），自动用同步模式重发一次，不让用户手动切换。

**改动**：`AgentClient` 增加 SSE 分支与帧解析；`AgentPanel` 增加「当前气泡」概念以支持增量追加。

**验收**：
- 长回答时正文分段出现（不是最后一次性刷出）；
- `thinking` 与正文视觉可区分（若 Agent 不产生则不显示）；
- 断流时能看到明确错误，而不是空白；
- 首字延迟不超过同步模式太多（实测基线约 5.6s，见 1.1.1）。

**风险**：`wxWebRequest` 的 data 事件在 3.2.6 上的行为需实测；若不可用，改用「只取最终响应 + 本地按句渐显」的伪流式，观感接近且实现简单得多。

#### 1.2 会话管理

- 面板增加「新对话」按钮：`ResetSession()` + 清空输出区；
- 标题栏显示会话短 ID（后 6 位），让人知道当前在哪个会话；
- 会话切换时清空 `drawnTexts` 缓存等 UI 状态。

**验收**：点「新对话」后，下一次提问的入参里没有 `sessionId`（可用网关日志核对）。

#### 1.3 注册重试与连接状态

- 注册失败按 1s / 2s / 4s / 8s 退避重试，最多 4 次；
- 状态标签四态：`未连接`（灰）/ `已连接`（绿）/ `重试中 n`（橙）/ `失败`（红）；
- 提供「重连」按钮，随时手动触发注册。

**验收**：先起客户端、后起网关，客户端能自动连上，无需重启。

#### 1.4 工具执行反馈

`AgentToolServer` 在 `ProcessBody` 前后通过 `CallAfter` 通知面板：

```text
正在读取画布…
已返回 3 个图元
```

**验收**：Agent 调用 `get_circuit` 期间，状态栏有对应文案。

### 阶段 2：只读能力扩展（P1）

#### 2.1 导出画布文字标注

`ToAgentJson` 增加：

```json
"texts": [ { "text": "这是与门", "position": [120, 80] } ]
```

数据源是 `DrawBoard::drawnTexts`（已有，只是没导出）。

#### 2.2 网络（net）级结构

`connections` 是边，`nets` 是连通分量。在 `CircuitReport` 里加：

```json
"nets": [
  {
    "id": "net-1",
    "pins": [ {"shape":"SinkPin-1","port":"input"}, {"shape":"AndGate-1","port":"in0"} ],
    "wires": [ "Line-1" ]
  }
]
```

实现：把 `connections` 里的 wire-pin / wire-wire 当作无向边，做并查集，输出连通分量。纯逻辑，可单测。

价值：模型能直接说「输入引脚 A 和与门的下输入在同一个网络里」，而不是逐个比较坐标。

#### 2.3 `export_png` 工具

参数：`{ "scale": 1.0 }`（可选，默认 1.0）。

难点：现有 `SaveAsPNG()` 弹文件对话框。需要抽出一个无对话框版本：

```cpp
// DrawBoard
bool ExportToFile(const wxString& path, double scale = 1.0);
```

复用现有渲染路径（`wxBitmap` + `wxGraphicsContext`），只是把目标从对话框换成给定路径。

返回：`{ "path": "...", "width": 800, "height": 600 }`。

**验收**：Agent 调用后磁盘上真的出现 PNG，且内容与画布一致。

### 阶段 3：受控写操作（P2）

#### 3.1 `set_output_pin`

```json
{ "shapeId": "SinkPin-1", "value": "High" }
```

实现链路（已验证可行）：

```cpp
gate->inputPinPoints[i].value = PinValue::High;
gate->UpdateDrawingDetails();   // public，内部会调 private 的 CalOutput() 重算输出
board->Refresh();
```

注意 `CalOutput()` 是 private，**只能**通过 public 的 `UpdateDrawingDetails()` 触发，不要试图改成 public。

#### 3.2 级联重算

改一个输入引脚，下游门的输出要跟着变。按 `connections` 建图做 BFS，逐级 `UpdateDrawingDetails()`。

必须先执行 `get_circuit` 拿到最新连接关系，不能假设客户端内存里的图是最新的。

#### 3.3 `add_component`

```json
{ "type": "AndGate", "position": [300, 200] }
```

复用现有 `ShapeFactory`，通过 `DrawBoard::AddShape()` 加入（该接口会进 Undo 栈）。

#### 3.4 写操作的安全约束

| 约束 | 原因 |
| --- | --- |
| 禁止 `ResetCanvas` / 批量删除 | 一次误操作就毁掉用户全部工作 |
| 每个写工具返回 `undoable: true` 和变更摘要 | 用户知道发生了什么、能反悔 |
| `system prompt` 要求说明改了什么 | 避免「静默改电路」 |
| 单次对话最多 5 次写操作 | 防止模型反复试错把画布搞乱 |
| 重算后立即 `ToAgentJson` 回读并校验 | 确认改动真的生效，而不是以为生效 |

**验收**：
- 让 Agent「把第一个输入引脚改成高电平」，画布变化且可 Ctrl+Z 撤销；
- 改完后 Agent 能正确说出输出引脚的新状态。

## 6. 关键设计说明

### 6.1 为什么 SSE 要 `SetStorage(Storage_None)`

`wxWebRequest` 默认把响应体存到内存或文件，只在完成时通知。只有设为 `Storage_None` 才会边收边发 `wxEVT_WEBREQUEST_DATA`。这是实现流式的前提，漏了就会「一次只收到最后一段」。

### 6.2 为什么写操作要回读校验

`UpdateDrawingDetails()` 的重算依赖 `PinMapManager` 里的连线关系，而连线可能因为坐标漂移没被识别。改完立刻 `ToAgentJson` 回读，能第一时间发现「以为改了、其实没生效」。

### 6.3 工具数量控制

工具越多，模型选错概率越高。计划把工具总数控制在 6 个以内：

| 工具 | 类型 |
| --- | --- |
| `get_circuit` | 只读 |
| `export_png` | 只读 |
| `set_output_pin` | 写 |
| `add_component` | 写 |
| `remove_component` | 写 |
| `connect_pins` | 写 |

超出这个数量就要考虑合并或分层（比如 `batch_edit` 一次改多个）。

## 7. 风险与缓解

| 风险 | 缓解 |
| --- | --- |
| SSE 在 wxWebRequest 上行为不符预期 | 保留同步路径自动降级；先做 1.1 的可行性验证再投入 |
| SSE 收益被高估（首字仍要 5s+） | 验收标准按 1.1.1 的实测基线定；另配「已等待 Ns」计时器兜住体感 |
| 写操作破坏用户电路 | Undo 栈 + 禁止批量 + 次数上限 + 回读校验 |
| 级联重算出错 | 回读校验；必要时改为「全画布重算」 |
| 工具变多导致模型选错 | 工具数 ≤ 6；描述写清使用场景 |
| 语义层与画布耦合变重 | 保持 `CircuitReport` 纯逻辑、可单测 |
| 回调线程与 UI 线程冲突 | 沿用现有 `CallAfter` + `promise` 模式，不直接碰控件 |

## 8. 交付节奏

```text
阶段 1（1.1 → 1.2 → 1.3 → 1.4）
   ↓ 验收：流式可见、能开新会话、网关晚启动能自愈
阶段 2（2.1 → 2.2 → 2.3）
   ↓ 验收：能看到文字标注、能说清网络、能出图
阶段 3（3.1 → 3.2 → 3.3 → 3.4）
   ↓ 验收：能受控改电路且可撤销
```

每个子项完成后单独提交，便于回滚。

## 9. 验收总表

| # | 验收项 | 判定方式 |
| --- | --- | --- |
| 1 | 流式输出 | 长回答分段出现，首字延迟 ≈ 同步模式 |
| 2 | thinking 区分 | 与正文颜色/字体不同（不产生则不显示） |
| 3 | 新会话 | 入参里无 `sessionId` |
| 4 | 注册自愈 | 先客户端后网关，能自动连上 |
| 5 | 工具反馈 | 调工具时状态栏有文案 |
| 6 | 文字标注 | `get_circuit` 返回里有 `texts` |
| 7 | 网络结构 | 返回里有 `nets`，且与肉眼连线一致 |
| 8 | 导出 PNG | 磁盘出现文件且内容正确 |
| 9 | 改引脚 | 画布变化 + 可撤销 + Agent 复述正确 |
| 10 | 加元件 | 新元件出现在指定坐标 |

## 10. 暂不做

- 多设备 / 多用户（当前单客户端单用户，CAP 层已支持，客户端侧没有需求）；
- 工具注销（网关支持，但客户端生命周期内不需要）；
- 本地缓存电路历史（属于编辑器功能，不是接入范围）。

## 11. 下一步

网关侧 SSE 已验证可用（见 1.1.1 实测），剩下的是客户端侧：
用 `wxWebRequest` 对 `/chat` 发 `Accept: text/event-stream`，确认 `wxEVT_WEBREQUEST_DATA` 能收到增量帧。
这一步是阶段 1.1 的**前置门槛**，验证通过再投入完整实现，否则按伪流式方案降级。

建议实施顺序（按收益/成本比）：

1. **1.2 会话管理 + 1.3 注册重试**：改动小、收益直接，不依赖 SSE 结论；
2. **1.1 SSE 流式**：先做 data 事件可行性验证；
3. **2.2 网络结构**：纯逻辑，可单测，能明显提升模型回答质量；
4. **阶段 3 写操作**：价值最高但风险最大，放在最后。

## 12. 实施状态（2026-10-27 晚，四步已落地）

### 12.1 1.2 会话管理 + 1.3 注册重试

| 改动 | 位置 |
| --- | --- |
| 五态状态机 `Disconnected/Registering/Connected/Retrying/Failed` | `AgentClient` |
| 退避重试 1s→2s→4s→8s，最多 4 次 | `AgentClient::ScheduleRetry` |
| 「新对话」「重连」两个按钮 | `AgentPanel` |
| 只有 `Connected` 时才放开输入框和发送按钮 | `AgentPanel::UpdateControls` |

细节：`State_Cancelled` 不再当作失败（避免手动重连时取消旧请求触发递归重试）；
注册失败只在**重试用尽**后才回调面板，避免刷屏。

### 12.2 1.1 SSE 流式

| 改动 | 位置 |
| --- | --- |
| SSE 帧解析（跨包/CRLF/多行 data/逐字节） | `SseParser`（纯标准库） |
| `ChatStream()`：`Accept: text/event-stream` + `Storage_None` | `AgentClient` |
| 增量渲染：流式期间纯文本，`final` 后删掉重绘为 Markdown | `AgentPanel` |
| 兜底：一帧都没收到时自动改发同步请求 | `AgentClient::FallbackToSync` |

帧类型以 `data.type` 为准，不依赖 `event:` 行；`thinking` 用灰色区分。

### 12.3 2.2 网络（net）结构

`CircuitReport::BuildNets()`：把 `connections` 当无向边做并查集，输出连通分量。

```json
"nets": [
  { "id": "net-1",
    "pins": [{"shape":"SinkPin-1","port":"input"}, {"shape":"AndGate-1","port":"in0"}],
    "wires": ["Line-1"] }
]
```

只输出**含至少一个引脚**的分量：孤立导线没有电气意义，不算网络。
`summary` 里同步加上「连成 N 个网络」。

### 12.4 阶段 3 受控写操作

| 工具 | 实现 |
| --- | --- |
| `set_output_pin` | `SetSinkPinCommand`（进撤销栈）→ `RecalculateCircuit()` |
| `add_component` | 复用 `Add1ckCommand`（天然可撤销）→ 注册引脚 → 重算 |
| `connect_pins` | `AddLineCommand` → **按 id 找回副本** → `SetLineByPin` 绑定引脚 → 重算 |

`connect_pins` 的两个坑（都在 `DrawBoard::ApplyAgentEdit` 里有注释）：

1. `Add2ckCommand::Execute` 是**拷贝构造**入画布的，所以拿到的是副本，必须按 `id` 找回；
2. 找回后必须调 `PinMapManager::SetLineByPin()` 把端点绑到 `PinPoint`，否则线只是几何图形，
   **信号不会传播**——这是「画了线但电路不工作」的典型原因。

`port` 命名与 `connections` 一致：`input` / `output` / `in0` / `in1` / `out`。
回读校验 `verified.connected` 判断这条线是否真的被识别成连接。

安全约束（对照 §3.4）：

| 约束 | 落实 |
| --- | --- |
| 禁止 `ResetCanvas` / 批量删除 | 没注册这类工具，从入口就堵住 |
| 返回 `undoable` + 变更摘要 | ✅ |
| system prompt 要求说明改动 | `clientPrompt` 已加 |
| 单次对话写操作上限 | 进程级 20 次，超限直接拒绝 |
| 改完回读校验 | `set_output_pin` 返回 `verified.ok` |

重算链路：`UpdateDrawingDetails()`（内部触发 private 的 `CalOutput`）
→ `PinMapManager::SetLineByPin`（沿导线传值）→ 迭代 3 轮。

### 12.5 验证情况

**已实测**：

- `SseParser`：23 条断言，覆盖单帧/多帧/跨包切断/CRLF 中间切断/多行 data/
  忽略 event 行/Flush 残留/**逐字节输入**/真实网关抓包样本 —— 全绿；
- `CircuitReport`：21 条断言，覆盖连接容差（5.6px 命中、14px 拒绝）、
  网络合并（两段导线串成一个 net）、独立网络、孤立导线不计数、摘要文案 —— 全绿；
- 10 个源文件用真实 wx 配置头 `-fsyntax-only` 全部通过；
- 网关侧 SSE 帧格式用 curl 实测确认（见 §1.1.1）。

**未实测（本机没有 MSVC，无法运行 GUI）**：

- `wxEVT_WEBREQUEST_DATA` 在 wx 3.2.6 + WinHTTP 下是否真的逐块触发 ——
  代码已按协议实现，并有「一帧未收到就降级同步」的兜底；
- 写操作在真实画布上的重算效果与 Ctrl+Z 撤销；
- 流式期间删掉临时区域再重绘的视觉过渡。

这些需要用户在 VS 2022 里重新生成后按 §9 验收表逐项确认。

### 12.6 与方案原始设计的偏差

| 原设计 | 实际实现 | 原因 |
| --- | --- | --- |
| `AddShape()` 会进撤销栈 | 不会，它直接 push_back | 实际用 `Add1ckCommand` 才有撤销 |
| SSE 首要收益是「更快看到第一个字」 | 首字延迟不变（6s 左右） | 实测发现耗时在 CLI 启动（§1.1.1） |
| `export_png`、`texts`、`remove_component` | 未做 | 本轮只完成四项；工具数控制在 4 个 |

### 12.7 关于连线（补充）

原方案把 `connect_pins` 列在阶段 3，本轮补上了。理由很直接：**能加门却不能连线，
写操作等于断了一半**——用户还得手动把线画上，Agent 的能力就停在「摆元件」。

实现上没有让 `add_component` 带 `Line`，而是单独做 `connect_pins`：
只给两个坐标无法保证线端点和 `PinPoint` 绑定成功，给「连哪两个引脚」才是可校验的语义。

### 12.8 系统提示词：注册时传了，但之前根本没生效

**问题**：客户端在 `/clients/register` 里传了 `clientPrompt`，`ClientRegistry` 也存下来了，
但 `ChatController` 只读本轮 `payload.clientPrompt`——而客户端 chat 时从不传，
于是 `AgentRequest.clientPrompt` **恒为 null**，Agent 一条客户端提示词都没收到。

之前 Agent 能正确调用工具，靠的是 MCP `tools/list` 里的工具描述，不是这段提示词。

**修复**（网关侧，两处）：

1. `ChatController`：本轮没传时回退到注册时声明的值
   ```java
   if (clientPrompt == null) {
       clientPrompt = clientRegistry.getClient(clientId)
               .map(ClientRegistration::clientPrompt)
               .filter(p -> p != null && !p.isBlank())
               .orElse(null);
   }
   ```
   这也更符合 `ChatPayload.clientPrompt` 注释里的「本轮**覆盖**」语义。

2. `CliAgentAdaptor.buildAppendedPrompt()`：把换行压成 `；`。
   CLI 在 Windows 下经 `cmd /c` 传参，**参数内不能含换行**，否则命令被截断。
   客户端提示词写成多行更好维护，压缩由网关兜底。
   （ACP 适配器不受此限，它把提示词放进 `session/prompt` 的 text block，支持多行。）

**客户端提示词也重写了**：原来只有一句话「你是电路设计助手…」，现在覆盖以下要点：

| 要点 | 作用 |
| --- | --- |
| 三类图元 `component` / `wire` / `drawing` | 避免把矩形当元件分析 |
| port 命名 `input`/`output`/`in0`/`out` | 让它能直接构造 `connect_pins` 参数 |
| `High`/`Low`/`Off` 的含义 | 尤其 `Off` = 未驱动或悬空 |
| 连接关系以 `connections`/`nets` 为准，不要自己比坐标 | 把几何计算固定交给客户端 |
| 每次先 `get_circuit`，不凭上文猜状态 | 电路会被用户改动，历史不可信 |
| 改前说明、一次只改一处、改后回读 | 写操作纪律 |
| 中文、先结论、用 id 指代、不编造 | 输出风格 |

**实测验证**（18081 临时实例）：

```text
[HTTP] 入参: { "payload": { "agent":"cli", "clientId":"prompt-test",
  "endUserId":"u1", "prompt":"回复两个字：收到" } }        ← 没有 clientPrompt

[Adaptor][cli] 启动CLI进程: cmd /c qwen.cmd -p 回复两个字：收到 ...
  --append-system-prompt 第一段：你是测试助手。；第二段：画布有三类图元。；第三段：回答用中文。；请始终使用简体中文回答用户，不要输出英文。
```

回退生效 + 三行成功压成单行，两项都验证到了。

**代价**：提示词每轮都会注入，按 CLI 适配器是拼进命令行参数，会计入上下文 token。
所以内容要精炼——当前约 300 字，不要再往里堆细节，领域知识应该放到工具的
`description` 或返回结构里，那才是按需加载的。
