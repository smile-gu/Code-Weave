# ADR-0013：Session 正常关闭与仓库所有权释放

状态：当前有效。日期：2026-09-08。

## 目标与边界

正常关闭结束当前 Session 的持续开发，不代表 Review 或验证通过。保留真实代码、未提交修改、Claude 原生上下文和全部历史，不执行 Git 提交、推送、回滚或清理。不自动取消，不提供 force 或 reopen；FAILED 与 Legacy 不由正常关闭解除。

## 状态与门禁

复用 ACTIVE → CLOSED，重复 CLOSED 仅补偿释放。首次写入 closedAt 与 updatedAt，旧快照缺 closedAt 保持 null。

关闭服务与 Turn 提交、基线推进共用 SessionOperationLockManager。首次关闭必须可靠读取 Turn 历史，确认没有 QUEUED/RUNNING、执行句柄、持久化阻断、资源阻断或 PROCESS_CLEANUP_FAILED 历史证据。Future 已完成或 Turn 已落终态不能代替句柄清理。

Review Harness 接入前，关闭门禁必须按 ADR-0009/0014 扩展：同一 Session 不得存在 Review 启动预留、`SUBMITTING/SUBMISSION_UNKNOWN/ACTIVE/FINALIZING/EXECUTION_BLOCKED` 产品记录，也不得存在 Python 非终态、事件/Result 导入未知或不安全 `resourceState`。只有 Python 已终态且资源为 `NOT_ACQUIRED/RELEASED`、Java 已可靠导入执行事实时，才可以解除执行资源门禁。报告仍在审阅但执行资源已经安全时允许正常关闭并保留历史；关闭后不得再从真实目录读取 Review 证据。

以上是 Review 接入时必须实现的扩展条件，不代表当前 Coding-only 关闭代码已经具备 Python 状态查询能力。

关闭不检查 HEAD、分支、脏工作区、Claude/Maven 就绪或验证结果，因为它只释放所有权，不修改代码。

## 非原子写入与补偿

关闭在同一 Session 锁内重读 → 门禁 → 保存 CLOSED → 按 repository + expectedSessionId 释放。

- 保存异常：重读与目标快照完全一致才继续，否则保留仓库保护，返回 SESSION_CLOSE_STATE_WRITE_FAILED。
- 释放异常：保留 CLOSED，返回 SESSION_CLOSE_RELEASE_PENDING。重试不改关闭时间。
- CLOSED 仅证明业务禁写；只有 close 的 HTTP 200 同时证明本次所有权释放已确认。
- 重启对 CLOSED 补偿释放，不恢复其写锁，不改其状态，不补造阶段/Turn 事件。单个补偿失败不阻断其他 Session。

## 稳定锁文件

所有者读取、校验和清空在独占文件锁内进行。没有本地句柄时也尝试获取文件锁并核对旧所有者；不同所有者不修改。无法获取锁、读取失败或元数据损坏均不能当作空闲。

正常释放保留空锁文件，避免释放后删除路径引起不同文件对象上的并发锁。本地句柄关闭失败保留跟踪；重试时区分仍持有锁、已失去锁和通道已关闭，绝不在无锁状态下清除所有者。服务停止也仅移除已成功关闭的句柄。

## HTTP 与页面

POST /api/v1/coding-sessions/{sessionId}/close，无请求体；200 返回完整 Session DTO（含 closedAt）。业务冲突 409，关闭保存/释放无法确认 503。客户端超时可以幂等重试。

关闭页保留对话与执行历史，隐藏输入、取消、基线推进、累计 Diff。Session changes 及兼容 Turn changes 对 CLOSED 返回 409 SESSION_CLOSED。平台不保存关闭时的 Diff 快照，不能把新 Session 对真实目录的修改归给旧会话。

网络失败后查询 Session 只确认业务状态，不推断锁释放；提供同一个 close 请求重试，不新增 SSE 或持续轮询。

## 故障窗口

| 窗口 | 恢复依据 |
|---|---|
| CLOSED 保存前 | ACTIVE 与持久仓库所有者仍受保护 |
| 保存后、释放前 | CLOSED 启动补偿旧所有者 |
| 所有者清空后、句柄关闭前 | 活跃文件锁阻止其他管理器获取；失败句柄保留重试，重启后空锁可补偿 |
| 释放后、响应前 | 同一请求再次确认，无状态回滚或时间重写 |

不引入跨文件事务、可靠消息系统、持久释放标志或孤儿进程管理。
