# 架构决策记录索引

| 编号 | 决策 | 状态 | 当前意义 |
|---|---|---|---|
| [ADR-0001](0001-claude-code-adapter-m0.md) | Claude Code Adapter M0 契约 | 历史有效 | 仍约束旧单轮 M0 Run；Session 已演进为上下文续接模式 |
| [ADR-0002](0002-m1-async-run-api.md) | 异步 Run API | 已演进 | 异步接入仍保留，实时展示已由 ADR-0004 的 SSE 取代轮询主通道 |
| [ADR-0003](0003-m2-run-result-api.md) | Run 结果与 Diff API | 历史有效 | 旧 M0 Run 回归接口仍使用 |
| [ADR-0004](0004-m3-sse-run-stream.md) | SSE 实时事件通道 | 当前有效 | 同一套事件基础设施也服务 Coding Turn |
| [ADR-0005](0005-m4-runtime-readiness.md) | 本机环境就绪检查 | 当前有效 | Session 提交 Turn 前继续执行必要检查 |
| [ADR-0006](0006-agent-event-noise-reduction.md) | Claude 高频事件降噪 | 当前有效 | 同时保护磁盘日志、SSE 和浏览器内存 |
| [ADR-0007](0007-coding-session-persistence.md) | 长期 Worktree Session | 已被替代 | 保留 M5 历史背景，不再代表正式 Session 工作区模型 |
| [ADR-0008](0008-direct-workspace-and-review-baseline.md) | 真实工作区与活动审查基线 | 当前有效 | 规定正式 Coding、Review、自测和模块 Commit 的主链路 |
| [ADR-0009](0009-review-harness-service-contract.md) | Review Harness 跨服务运行契约 | 部分冻结 | 记录 Java/Python 内部接口、Run 生命周期及 Java、Python、LangGraph、Memory 的持久化职责分层 |
| [ADR-0010](0010-legacy-worktree-run-isolation.md) | 旧版 Worktree Run 隔离 | 当前有效 | 旧版单轮链路只在显式 Profile、独立目录与独立回归测试中存在，不进入正式运行面 |

```mermaid
flowchart LR
    A[0001 Adapter] --> B[0002 异步 Run]
    B --> C[0003 结果与 Diff]
    C --> D[0004 SSE]
    D --> E[0005 环境检查]
    E --> F[0006 事件降噪]
    F --> G[0007 长期 Worktree]
    G -->|被替代| H[0008 真实工作区与活动基线]
    H --> I[0009 Review Harness 跨服务契约]
    I --> J[0010 旧版 Worktree Run 隔离]
```

## 状态含义

| 状态 | 含义 |
|---|---|
| 当前有效 | 仍是正式主链路必须遵守的决策 |
| 历史有效 | 对保留的旧链路仍成立，但不是新功能默认方案 |
| 已演进 | 核心思想保留，部分实现已被后续 ADR 扩展 |
| 已被替代 | 仅用于理解历史，新代码必须遵循替代 ADR |
| 部分冻结 | 已记录的条目可以实施，文档明确列出的其他条目仍需逐项讨论 |
