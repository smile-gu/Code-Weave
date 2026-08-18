# Coding Review Platform

一个 Coding-Review 一体化 Agent 平台。Coding Runtime 复用 Claude Code，Review Harness、工作流控制、可观测能力和评测系统由本项目独立开发。

## M0 范围

M0 用于验证以下技术链路是否可行：

```text
Git repository -> isolated Worktree -> Claude Code -> Git Diff -> Maven verification
```

当前已经完成 M0 主链路、异步 Run API、SSE 实时事件通道和首版 Web 工作台：Control Plane 可以创建隔离 Worktree，调用受限 Claude Code，采集 Git Diff，执行路径门禁与 Maven 验证；用户可以通过浏览器提交任务并实时查看五阶段进度、业务结果和代码变更。

M5 新增了持续 Coding 的持久化底座。一个 `CodingSession` 长期拥有一个独立 Worktree，服务重启后仍可查询和恢复。当前尚未实现 Session 内的多轮 Coding Turn、Claude Code 上下文续接和 Review。

## 仓库结构

```text
apps/web-console/             React + TypeScript Web 工作台
services/control-plane/       Java Spring Boot 工作流控制平面
services/review-harness/      Python Review Harness（预留）
workers/                      语言分析 Worker（预留）
contracts/schemas/            跨语言共享的 JSON Schema
fixtures/spring-boot-demo/    M0 使用的隔离目标项目
benchmarks/spring-boot/       Review 评测用例（预留）
runtime/                      本地运行数据，Git 默认忽略
docs/decisions/               已冻结的技术决策
```

## 环境要求

- Java 21
- Maven 3.9+
- Git 2.40+
- Claude Code CLI 2.1+

## 验证项目

```powershell
.\mvnw.cmd test
```

## 启动 Control Plane

服务只监听本机 `127.0.0.1`。运行目录与 Claude Code 可执行文件属于部署配置，不能由单次 HTTP 请求指定：

```powershell
$env:CODE_WEAVE_RUNTIME_ROOT = "D:/code-weave-runtime"
$env:CODE_WEAVE_CLAUDE_EXECUTABLE = "C:/path/to/claude.cmd"
.\mvnw.cmd -pl services/control-plane spring-boot:run
```

当前提供以下接口：

```text
POST /api/v1/m0/runs
GET  /api/v1/m0/runs/{runId}
GET  /api/v1/m0/runs/{runId}/events?afterSequence=0
GET  /api/v1/m0/runs/{runId}/stream?afterSequence=0
GET  /api/v1/m0/runs/{runId}/result
GET  /api/v1/m0/runs/{runId}/changes
GET  /api/v1/system/readiness
POST /api/v1/coding-sessions
GET  /api/v1/coding-sessions?limit=20
GET  /api/v1/coding-sessions/{sessionId}
```

`stream` 使用 SSE 实时发送 Run 事件，并支持通过 `Last-Event-ID` 或 `afterSequence` 断点恢复。`events` REST 接口继续用于排障和手动重放。

`readiness` 使用只读命令检查 Runtime Root、Git、Java、Maven 和 Claude Code。创建 Run 前会重新检查，必要项失败时返回 `503 RUNTIME_NOT_READY`，不会创建 Run 或 Worktree。

Windows 环境应将 `CODE_WEAVE_CLAUDE_EXECUTABLE` 指向 `claude.cmd`，不要配置 PowerShell 默认解析到的 `claude.ps1`：

```powershell
$env:CODE_WEAVE_CLAUDE_EXECUTABLE = "D:/develop/nodejs/claude.cmd"
```

## 启动 Web 工作台

前端开发服务器会将 `/api` 代理到本机 `127.0.0.1:8080`，因此不需要开放跨域访问：

```powershell
cd apps/web-console
npm install
npm run dev
```

浏览器访问 `http://127.0.0.1:5173`。一次性 Run 位于 `/`，持续会话入口位于 `/sessions`。Web 首版需要手动输入本地 Git 仓库的绝对路径，原生文件夹选择将在桌面端实现。

默认只允许 1 个 Run 同时执行，最多排队 10 个。一次性 Run 状态保存在当前服务进程内，服务重启后状态查询会丢失；NDJSON 事件和任务产物仍保留在运行目录中。

Coding Session 使用版本化 JSON 快照持久化：

```text
runtime/sessions/{sessionId}/session.json
runtime/worktrees/{sessionId}/
```

启动时会将中断在 `INITIALIZING` 的 Session 标记为失败，并检查 `ACTIVE` Session 的 Worktree 是否仍然存在。完整设计见 [ADR-0007](docs/decisions/0007-coding-session-persistence.md)。
