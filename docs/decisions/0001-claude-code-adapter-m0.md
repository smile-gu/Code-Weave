# ADR-0001：ClaudeCodeAdapter M0 契约

- 状态：M0 已采纳
- 日期：2026-08-13

## 背景

平台需要以可复现、可观测的方式调用用户现有的 Claude Code CLI，同时不能修改其全局配置。在开放更广泛的 Coding 能力之前，M0 必须先验证环境隔离、协议解析、任务取消和结果验证这几项基础能力。

## 决策

Adapter 使用可配置的 `CapabilityProfile`。M0 选择 `M0_RESTRICTED`；这些限制只服务于当前验证阶段，不代表最终产品的能力上限。

`M0_RESTRICTED` 使用以下参数：

```text
--print
--input-format text
--output-format stream-json
--verbose
--no-session-persistence
--safe-mode
--permission-mode dontAsk
--session-id <run UUID>
--max-budget-usd 1.00
--tools Read Glob Grep Edit Write
--allowedTools Read Glob Grep Edit Write
```

任务 Prompt 通过 UTF-8 标准输入发送，绝不放入命令行参数。进程工作目录固定为本次 Run 对应的 Worktree。

M0 明确禁用 Bash、Git 操作、Maven 执行、网络访问、用户 Skills、Hooks、MCP Server、插件以及 Claude 自带的 Worktree 管理。Git 和 Maven 由 Control Plane 独立执行。

## 事件事实来源

只有运行时实际发出的协议事件才能证明工具行为发生过。系统不采信“我读取了文件”或“测试已通过”这类文本声明。文件变更以 Git Diff 为准，最终验证结果以平台 Verifier 为准。

Claude 事件按下表映射为平台事件：

| Claude 事件 | 平台事件 |
|---|---|
| `system/init` | `agent.runtime.initialized` |
| `system/thinking_tokens` | `agent.usage.updated` |
| assistant `text` | `agent.message.completed` |
| assistant `tool_use` | `tool.call.started` |
| user `tool_result` | `tool.call.completed` or `tool.call.failed` |
| result success | `agent.runtime.completed` |
| result error | `agent.runtime.failed` |

Thinking 文本既不持久化，也不展示。

## 失败判定

Adapter 失败类型如下：

- `START_FAILED`：运行时无法启动。
- `START_TIMEOUT`：30 秒内没有收到 `system/init`。
- `WORKSPACE_MISMATCH`：初始化事件中的 `cwd` 与本次 Worktree 不一致。
- `TOOL_POLICY_VIOLATION`：初始化信息或后续事件暴露了未授权工具。
- `PROTOCOL_ERROR`：标准输出包含无效的 NDJSON 协议行。
- `INCOMPLETE_STREAM`：进程退出前没有产生终态结果。
- `AGENT_FAILED`：终态结果包含 `is_error=true`。
- `PROCESS_FAILED`：进程退出码不为零。
- `TIMED_OUT`：执行时间超过 10 分钟。
- `CANCELLED`：用户取消本次 Run。

单次工具调用失败不会直接导致 Adapter 失败，因为 Agent 可能自行恢复。Adapter 执行成功与 Coding 任务成功是两个不同结论。Coding 任务成功还必须满足：Git Diff 符合策略，并通过平台独立执行的 Maven 验证。

## Windows 注意事项

当前安装的 CLI 入口是 `claude.cmd`。Windows 启动方式和参数转义由平台专用的进程启动器封装。任何动态用户内容都不能拼接到 Shell 命令字符串中。

## 后续演进

后续 Capability Profile 可以逐步开放受限 Bash、可信项目指令、Hooks、Skills、MCP、会话恢复和 Subagent。Workflow Control Plane 始终负责全局生命周期与阶段门禁，Claude Code 则在 Coding 阶段内部保持自主执行能力。
