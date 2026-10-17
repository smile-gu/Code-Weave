# ADR-0005：M4 本机运行环境就绪检查

- 状态：M4 已采纳
- 日期：2026-08-18

## 背景

M0-M3 已经通过隔离 E2E CLI 验证 Coding Run 主链路，但在调用真实 Claude Code 前，平台需要区分本机依赖故障与 Agent 协议故障。若直接创建 Run，CLI 路径、工具版本或运行目录问题会延迟到异步工作线程中暴露，用户难以判断失败原因。

## 决策

Control Plane 提供实时只读接口：

```text
GET /api/v1/system/readiness
```

接口检查 Runtime Root、Git 2.40+、Java 21+、Maven 3.9+ 和 Claude Code 2.1+。Claude Code 除版本外，还必须在 `--help` 中暴露 Adapter 使用的固定参数。

接口在环境未就绪时仍返回 HTTP 200，通过响应体的 `READY` 或 `NOT_READY` 表达业务状态。每个检查项包含稳定组件标识、状态、版本、可执行文件、用户消息和修复建议。

创建 Run 时，`WorktreeRunApplicationService` 在生成 Run ID 和写入任何事件之前重新执行检查。必要项失败时返回：

```text
HTTP 503
code: RUNTIME_NOT_READY
```

此时不创建 Run、不写事件、不创建 Worktree，也不启动 Claude Code。

## 安全边界

- 探测命令以参数列表交给 `ProcessBuilder`，不通过 Shell 拼接。
- 仅执行固定的 `--version`、`-version` 和 `--help`。
- 不调用模型，不读取或修改 Claude Code 用户配置。
- 每个进程最多执行 5 秒，最多保留 64 KB 合并输出。
- 超时后按子进程优先顺序终止进程树。
- 原始进程诊断只写服务日志，不通过 Readiness API 暴露。

## Windows 启动入口

PowerShell 通常优先解析到 `claude.ps1`，但 Java 进程不能将 PowerShell 脚本作为 Claude Runtime 入口。Windows 部署配置必须指向 Java 可直接启动的 `claude.cmd`、`.bat` 或 `.exe`，当前开发环境使用：

```text
D:/develop/nodejs/claude.cmd
```

## 未选择方案

- 不使用 Spring Boot Actuator Health：Readiness 是带版本、路径和用户修复建议的产品 API，不只是部署探针。
- 不缓存检查结果：本地依赖可能在页面打开后发生变化，提交 Run 时必须基于当前事实重新检查。
- 不允许 HTTP 请求指定工具路径：可执行文件继续属于部署配置，避免请求覆盖进程边界。
- 不为了展示缺失配置而引入降级 Bean 图：Runtime Root 和 Claude 路径缺失仍属于 Control Plane 启动配置错误。

## 后续演进

真实 Claude Code E2E 通过后，可以根据实际启动耗时评估并行探测或短周期缓存。桌面端可以负责生成部署配置，但 API 与 Run 门禁语义保持不变。
