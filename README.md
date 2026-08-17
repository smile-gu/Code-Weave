# Coding Review Platform

一个 Coding-Review 一体化 Agent 平台。Coding Runtime 复用 Claude Code，Review Harness、工作流控制、可观测能力和评测系统由本项目独立开发。

## M0 范围

M0 用于验证以下技术链路是否可行：

```text
Git repository -> isolated Worktree -> Claude Code -> Git Diff -> Maven verification
```

当前已经完成 M0 主链路与 M1 异步 Run API：Control Plane 可以创建隔离 Worktree，调用受限 Claude Code，采集 Git Diff，执行路径门禁与 Maven 验证，并通过 HTTP 提交和查询任务。

## 仓库结构

```text
apps/                         Web 工作台（预留）
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

M1 提供以下接口：

```text
POST /api/v1/m0/runs
GET  /api/v1/m0/runs/{runId}
GET  /api/v1/m0/runs/{runId}/events?afterSequence=0
GET  /api/v1/m0/runs/{runId}/result
GET  /api/v1/m0/runs/{runId}/changes
```

默认只允许 1 个 Run 同时执行，最多排队 10 个。状态保存在当前服务进程内，服务重启后状态查询会丢失；NDJSON 事件和任务产物仍保留在运行目录中。
