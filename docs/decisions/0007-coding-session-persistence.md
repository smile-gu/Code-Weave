# ADR-0007：Coding Session 持久化与长期 Worktree（已被替代）

- 状态：已被 [ADR-0008](0008-direct-workspace-and-review-baseline.md) 替代
- 日期：2026-08-18

> 本文保留 M5 阶段的历史设计。正式 Coding Session 已改为直接绑定真实 Git 工作区；长期 Worktree、自动 Commit 与 Fast-forward 合并不再是当前产品方案。文件快照、Session/Turn 持久化和启动恢复思想仍然保留。

## 背景

M0 Run 是一次性任务：接收一条任务说明，创建 Worktree，执行 Claude Code、变更采集、路径门禁和 Maven 验证，随后结束。最终产品需要支持持续 Coding，并在一个模块完成后插入 Review，再将 Review 结论交给 Coding Agent 整改。

因此，后续业务主体需要从一次性 Run 提升为长期 Session：

```text
CodingSession
  ├─ CodingTurn（后续阶段）
  ├─ ReviewCheckpoint（后续阶段）
  ├─ ReviewRun / ReviewReport（后续阶段）
  └─ RemediationTurn（后续阶段）
```

## 决策

一个 `CodingSession` 在创建时获得一个独立 Git Worktree，并在多轮 Coding 与 Review 之间长期复用。Session 与 Worktree 使用同一个 `sessionId` 建立所有权关系，一次性 Run 的 `runId` 不再承担工作区所有者语义。

本阶段只建立 Session 持久化底座，不实现 Coding Turn、Claude Code `--resume`、Review、关闭、删除或合并原始仓库。

Session 使用以下状态：

- `INITIALIZING`：初始快照已经保存，Worktree 尚未可靠创建并登记；
- `ACTIVE`：Worktree 已创建且快照已可靠保存，可承载后续 Turn；
- `CLOSED`：为后续正常关闭流程预留；
- `FAILED`：Session 已无法安全继续使用，并携带稳定失败码和用户说明。

## 创建事务边界

创建流程采用可恢复的两阶段状态，而不是假装文件系统与 Git 支持数据库事务：

```text
保存 INITIALIZING
  -> 创建独立 Worktree
  -> 保存 ACTIVE
```

- Worktree 创建失败：将快照更新为 `FAILED`，向 API 返回稳定业务错误；
- Worktree 已创建但 `ACTIVE` 保存失败：强制清理尚未投入使用的 Worktree，并保留可由启动恢复识别的初始化快照；
- 错误响应只返回用户可操作说明，Git 命令和路径诊断只进入服务日志。

## 文件快照

首版不引入数据库，Session 事实保存在运行目录：

```text
runtime/
  sessions/{sessionId}/session.json
  worktrees/{sessionId}/
```

`session.json` 使用显式 `schemaVersion = 1`。保存时先写入同目录唯一临时文件，通过 `FileChannel.force(true)` 刷盘，再使用原子移动替换正式快照；文件系统不支持原子移动时降级为普通替换。无论成功或失败，都尝试清理临时文件。

持久化文档单独定义，不直接序列化领域对象。`Path` 以字符串保存，读取后重新构造并通过领域校验，避免 Java 实现细节成为永久数据协议。

## 启动恢复

Control Plane 启动时扫描所有 Session：

- `INITIALIZING` 转为 `FAILED / SESSION_INITIALIZATION_INTERRUPTED`；
- `ACTIVE` 但 Worktree 目录丢失时转为 `FAILED / SESSION_WORKTREE_MISSING`；
- 其他有效 Session 保持原状态；
- 单个 JSON 损坏或版本不兼容时记录日志并跳过，不能阻止其他 Session 恢复和服务启动。

恢复只判断平台能够确定的事实，不自动重新创建 Worktree，也不猜测用户希望如何处理丢失的工作目录。

## API 与前端边界

本阶段提供：

```text
POST /api/v1/coding-sessions
GET  /api/v1/coding-sessions?limit=20
GET  /api/v1/coding-sessions/{sessionId}
```

Web 工作台新增 `/sessions` 和 `/sessions/{sessionId}`，用于创建会话、查看最近历史和工作区详情。页面必须明确提示多轮 Coding Turn 尚未开放，避免将“持久化底座”误解为已经支持持续对话。

## 选择文件持久化的理由

- 当前是单机、本地优先的 MVP，没有多实例并发写入需求；
- Session 数据量小，按最近更新时间读取即可满足首版历史页面；
- JSON 快照便于开发阶段检查、手工恢复和理解状态变化；
- 先稳定领域模型和恢复语义，未来迁移数据库时仓储接口可以保持不变。

## 迁移数据库的触发条件

出现以下任一需求时，应重新评估 SQLite 或服务端数据库：

- Session、Turn 和 Review 报告需要组合查询、分页或全文搜索；
- 多进程或多实例需要并发读写；
- 需要事务性关联 Session、Turn、事件索引和评测结果；
- 文件扫描和全量排序成为可观测的性能瓶颈。

数据库迁移不能改变 Session 对长期 Worktree 的所有权，也不能把运行时路径直接暴露为跨机器可迁移资源。
