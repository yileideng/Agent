# Codex Agent

Gateway 同时保留 `cli`（Qwen Code）并注册 `codex`（Codex app-server）。现有 `/agents` 目录和聊天接口的 `agent` 参数即可选择；未传 `agent` 时仍使用 `agent.default-adaptor`。

切换 Agent **不会**换会话：Gateway 用一份持久化的共享会话历史记录用户输入、正式回复和真实工具执行，新接手的 Agent 只补它没看到的部分。设计见 [docs/shared-agent-conversations.md](docs/shared-agent-conversations.md)。数据默认写在 `data/conversations`，该目录不入库；`agent.conversation.directory` 与 `agent.conversation.context-max-chars` 可覆盖。

## 配置

所有 Codex 配置均独立于 `agent.cli`，无需修改 Qwen 配置。运行 Gateway 的账户须已经通过 Codex CLI 登录，Gateway 复用该账户的 CLI 凭据，不写登录信息、密钥或全局配置。

```powershell
$env:AGENT_CODEX_COMMAND = 'C:\Users\18101\AppData\Local\Programs\OpenAI\Codex\bin\codex.exe'
$env:AGENT_CODEX_MCP_URL = 'http://127.0.0.1:8080/mcp'
# 可选：省略 model/provider 使用 CLI 当前配置
# $env:AGENT_CODEX_MODEL = '你的模型名称'
# $env:AGENT_CODEX_MODEL_PROVIDER = '已在 Codex CLI 中定义的 provider 名称'
# 本机现有 Magpie provider 可用请求级覆盖选择：
# $env:AGENT_CODEX_MODEL_PROVIDER = 'magpie'
# $env:AGENT_CODEX_MODEL = 'gpt-6.1-sol'
```

设置环境变量后由父进程正常启动 Gateway。也可用 Spring 的 `--agent.codex.command=...` 等启动参数覆盖。`command` 是一个原生可执行文件路径或 PATH 中的命令名，不是包含参数的 shell 命令，也不接受 npm `.cmd` shim；Windows 推荐直接配置 Codex 的 `codex.exe`。

| 配置 | 环境变量 | 默认值 |
| --- | --- | --- |
| `agent.codex.command` | `AGENT_CODEX_COMMAND` | `codex` |
| `agent.codex.model` | `AGENT_CODEX_MODEL` | CLI 默认模型 |
| `agent.codex.model-provider` | `AGENT_CODEX_MODEL_PROVIDER` | CLI 默认 provider |
| `agent.codex.mcp-url` | `AGENT_CODEX_MCP_URL` | `http://127.0.0.1:8080/mcp` |
| `agent.codex.timeout-seconds` | `AGENT_CODEX_TIMEOUT_SECONDS` | `180` |
| `agent.codex.work-dir` | `AGENT_CODEX_WORK_DIR` | 系统临时目录的 `codex-agent-workspaces` |
| `agent.codex.language-prompt` | Spring 标准配置覆盖 | 简体中文约束 |

`agent.codex.model-provider` 对应 app-server schema 的 `modelProvider` 字段，每次 `thread/start` 和 `thread/resume` 都会重新传入；为空时保留 CLI 默认。它只选择已在该账户 Codex 配置中定义的 provider，沿用其既有 endpoint/认证，不复制或记录 provider 的认证字段，也不改变全局配置。

`command` 不存在时，Gateway 正常启动，`codex` 在 Agent 目录中显示不可用并提供配置提示，其他 Agent 继续工作。可执行文件存在只表示本机可启动，实际认证、模型可用性和 CLI 协议错误在本轮请求中报告。

## 协议与会话

以本机 Codex CLI **0.149.1** 的 `app-server generate-json-schema --out target/codex-schema` 输出核对字段。该命令只生成临时参考文件，不调用模型。升级 CLI 后应重新生成并检查协议。

每轮独立启动 `codex app-server --listen stdio://`，发送 `initialize`、`initialized`，然后 `thread/start` 或 `thread/resume`，最后 `turn/start`。正文 `item/agentMessage/delta` 立即透传到 Gateway sink；`item/completed` 仅补发没有增量的正文 item，避免重复；`turn/completed` 根据状态结束或报告错误。支持 reasoning summary 增量。JSONL 通过 UTF-8 stdin/stdout 交换，多行提示词不经 shell。

Gateway 的 `AgentSessionStore` 在 `codex` 命名空间持有 thread ID，与 `cli` 隔离。相同聊天按会话状态串行执行，续聊仍重新设置客户端提示词、语言约束、工作目录和本轮 MCP 地址。会话工作目录通过客户端、用户、会话的 SHA-256 标识隔离。Gateway 会话映射目前仅保存在内存，重启或 TTL 到期后不会自动恢复旧映射；Codex 自身按 CLI 默认持久化 thread。

MCP 使用 thread 参数的临时 `config.mcp_servers.client-tools.url`，地址为：

```text
{mcp-url}/{clientId}?endUserId=...&sessionId=...&chatId=...
```

每轮更新 `chatId`，复用现有 MCP Streamable HTTP 路由与客户端工具目录。MCP 工具由 Codex 原生 MCP 客户端发现和执行，不需要额外的 Gateway 工具调度实现。`mcp-url` 为空可禁用该注入。

配置了客户端 MCP 时，Gateway 在每轮 `thread/start` / `thread/resume` 后等待匹配线程及 `client-tools` 服务的 `mcpServer/startupStatus/updated` 状态达到 `ready`，随后调用 `mcpServerStatus/list` 确认目录查询成功，再发送 `turn/start`。合法空工具目录仍可普通聊天；MCP 启动失败、取消、发现错误或等待超时会明确报错。就绪等待沿用同一个请求 deadline。

该请求的 `client-tools` 配置还设置 `default_tools_approval_mode=approve`，只授权当前已按客户端路由的 MCP 工具非交互调用；全局 `approvalPolicy=never` 和本机 shell `read-only` 保持不变。否则 Codex 默认工具审批可能在模型选中工具后返回 `MCP tool call requires approval, but approval policy is never`，实际调用不会到达客户端。字段参见 [Codex MCP 配置文档](https://developers.openai.com/codex/mcp) 与 [配置参考](https://developers.openai.com/codex/config-reference)。

本机 shell 固定为 `sandbox=read-only`，审批固定为 `approvalPolicy=never`。不使用跳过沙箱参数。若服务端仍请求 shell/文件审批或 MCP elicitation，Gateway 显式拒绝；其他交互式 RPC 返回不支持。客户端工具仍可按用户请求修改客户端状态，read-only 沙箱限制的是本机 shell 文件操作。请求超时、中断、错误和正常结束均销毁本轮 app-server 及其子进程。stderr 不回传，避免 CLI 诊断意外泄露本机凭据信息。

## 验证

普通测试仅启动 deterministic fake Java CLI 子进程，不访问模型；另有临时本地 HTTP MCP stub 验证 initialize、tools/list、tools/call 与完整路由参数。

```powershell
& 'D:\Maven\apache-maven-3.9.9\bin\mvn.cmd' test
```

显式开启真实只读文本与续聊 smoke（会调用两轮模型，默认跳过；请传入当前账号实际支持的模型）：

```powershell
& 'D:\Maven\apache-maven-3.9.9\bin\mvn.cmd' '-Dtest=CodexAgentAdaptorTest#realTextSmokeOnlyWhenExplicitlyRequested' '-Dcodex.smoke.command=C:/Users/18101/AppData/Local/Programs/OpenAI/Codex/bin/codex.exe' '-Dcodex.smoke.model=gpt-6.1-sol' '-Dcodex.smoke.model-provider=magpie' test
```

该 smoke 要求回复 `CODEX_OK`，随后续聊确认记住的口令，不连接客户端工具或 Gateway 服务。另可用 `-Dtest=CodexAgentAdaptorTest#realMcpDiscoveryOnlyWhenExplicitlyRequested` 验证真实 app-server 的 MCP 发现与显式工具 RPC：它只连接测试启动的临时只读 HTTP stub，不调用模型、不修改画布。`#realMcpModelToolSmokeOnlyWhenExplicitlyRequested` 则通过完整适配器让真实模型自动选择并调用该 stub 工具，断言服务确实收到 `tools/call` 且模型正文使用返回值；命令、模型和 provider 参数同上。`#realModelListOnlyWhenExplicitlyRequested` 只读查询非敏感模型列表、账户类型与 provider。

默认 fake 子进程测试还验证 MCP 延迟就绪、错误线程的 ready 不得提前放行、合法空工具目录、启动失败/取消、目录 RPC 失败以及整体 deadline，确保异常时不会继续发送模型 turn。

本机原生 MCP 初始化、工具发现与显式工具 RPC 已通过。真实文本与跨进程续聊 smoke 也已通过：设置 `model-provider=magpie`、`model=gpt-6.1-sol` 后，首轮收到 `CODEX_OK`，第二轮新进程通过 `thread/resume` 正确记住口令。

此前仅覆盖模型但保持默认 `openai` provider 时曾返回 `model_not_found`。原因是本机模型目录属于现有 `magpie` provider，不能把自定义目录中的型号直接用于默认 provider。部署时将 `AGENT_CODEX_MODEL_PROVIDER=magpie` 与 `AGENT_CODEX_MODEL=gpt-6.1-sol` 配对即可复用现有本地代理；无需修改 CLI 全局配置、登录或密钥。

完整 HTTP 联调已通过：Codex 实际调用临时客户端的只读 MCP 工具，回调携带正确的客户端、用户、会话和 chatId；下一轮新进程续聊保留口令；原有 QwenCode `cli` 路由也正常。可显式运行（会产生真实模型请求，不修改用户画布）：

```powershell
python ./scripts/verify-codex-gateway.py
```

脚本创建唯一临时客户端，结束时注销，报告保存在 `target/codex-integration-report.json`。wxDemo 的真实 Codex 界面回归也通过：402 个增量，8622ms 正文可见，16077ms final 到达。

仓库中的 `scripts/start-codex-gateway.ps1` 可在已完成 Maven package 后作为本机可复现启动入口：自动发现 `codex.exe`，默认选择 `magpie` / `gpt-6.1-sol`，参数可覆盖；脚本本身不构建项目、不修改全局环境或登录。启动示例：

```powershell
& ./scripts/start-codex-gateway.ps1
```

