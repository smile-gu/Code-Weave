# ADR-0002：M1 异步 Run API 与进程内状态投影

- 状态：M1 已采纳
- 日期：2026-08-17

## 背景

M0 主工作流只能由 Java 代码同步调用，前端无法提交任务，也无法持续查看运行状态和事件。M1 需要提供最小可用的 HTTP 边界，同时不能让 Web 层侵入五阶段业务编排。

## 决策

M1 使用异步提交与轮询查询：

```text
HTTP Controller
    -> M0RunApplicationService
        -> bounded TaskExecutor
            -> M0RunOrchestrator
                -> Worktree -> Claude Code -> Diff -> Policy -> Maven
```

`POST /api/v1/m0/runs` 返回 `202 Accepted`。前端使用状态 URL 查询内存状态投影，并使用事件 URL 按 `sequence` 增量读取 NDJSON 事实。

应用服务拥有以下生命周期事件：

```text
RUN_CREATED -> RUN_QUEUED -> RUN_STARTED
```

编排器只拥有阶段事件与业务终态：

```text
STAGE_STARTED -> ... -> STAGE_COMPLETED / STAGE_FAILED
RUN_COMPLETED / RUN_FAILED / RUN_CANCELLED
```

这一调整避免编排器同时承担任务接入与业务执行两种职责。

## 状态与事实的区别

`RunExecutionRegistry` 是当前服务进程中的查询投影，保存：

- `QUEUED`
- `RUNNING`
- `COMPLETED`
- `FAILED`
- `CANCELLED`
- `INFRASTRUCTURE_FAILED`

`RunEventJournal` 是持久化事实源。状态查询追求快速，事件查询负责完整追溯。M1 不尝试在服务重启后用事件重建 Registry，因此重启后旧 Run 的状态接口返回 404，但磁盘事件不会丢失。

## 并发与过载保护

M1 使用有界线程池，默认并发数为 1、等待队列容量为 10。队列已满时：

1. 当前 Run 标记为 `INFRASTRUCTURE_FAILED`。
2. 记录 `RUN_FAILED` 事件，失败码为 `RUN_QUEUE_FULL`。
3. HTTP 返回 `503 Service Unavailable` 和对应 `runId`。

该设计让前端能够区分“请求参数错误”“任务不存在”“系统过载”和“内部故障”。

## 安全边界

HTTP 请求只能提供仓库、基准版本、任务说明、验收标准和路径策略。以下参数只能由服务部署配置提供：

- Claude Code 可执行文件
- 平台运行根目录
- Run 并发数与队列容量
- Claude Code 能力配置

服务默认只绑定 `127.0.0.1`，M1 不提供远程多用户访问能力。

## 暂不实现

- SSE 或 WebSocket 实时推送
- 主动取消
- 数据库持久化与重启恢复
- Diff 详情接口
- Worktree 合并与清理
- Review Harness

这些能力将在前端轮询链路验证稳定后分阶段加入。
