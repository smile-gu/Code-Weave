# ADR-0003：M2 Run 结果与代码变更查询 API

- 状态：M2 已采纳
- 日期：2026-08-17

## 背景

M1 允许前端提交 Run、查询状态和增量读取事件，但这些接口只能说明任务执行到哪里，不能直接展示完整业务结论和代码 Diff。Web 工作台如果只依赖 M1，只能提供过程监控，无法形成代码检查闭环。

## 决策

M2 增加两个只读接口：

```text
GET /api/v1/m0/runs/{runId}/result
GET /api/v1/m0/runs/{runId}/changes
```

`result` 返回 Coding、路径策略、Maven 验证和失败位置等结构化业务结论，但不包含完整 Diff。`changes` 返回 `GitChangeSet` 中已经采集的文件列表和 Unified Diff。

```text
RunExecutionRegistry
    -> M0RunResult
        -> RunResultResponse
        -> GitChangeSet -> RunChangesResponse
```

API 只映射显式响应 DTO，不直接序列化领域模型。以下内部信息不会出现在响应中：

- Claude、Maven 和 Run 的技术诊断
- Maven 启动命令
- Claude 终态原始事件
- 内部日志对象

## 输出可用性

Run 输出读取遵循以下规则：

| 场景 | HTTP 状态 | 错误码 |
|---|---:|---|
| Run 不存在 | 404 | `RUN_NOT_FOUND` |
| Run 尚未进入终态 | 409 | `RUN_NOT_FINISHED` |
| 基础设施失败且无业务结果 | 409 | `RESULT_UNAVAILABLE` |
| 任务在变更采集前结束 | 409 | `CHANGES_UNAVAILABLE` |
| 已采集到空变更 | 200 | 无 |

空变更是已经产生的业务事实，因此返回空文件列表和空 Diff，而不是错误。

## 安全边界

变更接口只接受 `runId`，不接受用户提供的文件路径、Worktree 路径或 Git 参数。它只返回平台先前产生并保存在 `M0RunResult` 中的 `GitChangeSet`，不会重新执行 Git，也不会读取任意本地文件。

Unified Diff 继续受 `GitChangeCollectorImpl` 的 2 MB 上限保护。M2 不改变原始仓库和 Worktree，也不增加新的进程执行能力。

## 暂不实现

M2 继续使用当前进程内的结果快照。服务重启后无法恢复结果查询，但 NDJSON 事件、Maven 产物和 Worktree 仍然保留。结果持久化、重启恢复和历史 Run 列表将在后续阶段单独设计。

完成 M2 后，Web 工作台可以基于状态、事件、结果和变更五个 API 构建完整的只读运行视图。
