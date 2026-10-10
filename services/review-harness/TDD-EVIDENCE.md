# 批次 1 红—绿证据

基线：`09bb078e35bf8f56de529cdefd1faa0722f87ef5`。测试使用真实临时文件 SQLite，竞态使用 Event/Barrier。

| 切片 | 实现前失败证据 | 实现后验证 |
|---|---|---|
| 配置、认证 | `pytest tests/unit/test_config.py tests/integration/test_auth.py -q`：2 个 collection errors，缺少 `review_harness` | 19 passed |
| 空库迁移 | `pytest tests/integration/test_migration.py -q`：缺少 persistence 模块 | 2 passed；补齐 SQLAlchemy asyncio 依赖 |
| RunStore 原子操作 | `pytest tests/integration/test_store.py -q`：缺少 domain 模块 | 首轮 11 passed，故障/资源/提交确认回归扩展至 20 passed |
| HTTP 用例 | `pytest tests/integration/test_api.py -q`：缺少 application 模块 | 9 passed |
| 网络 SSE | `pytest tests/integration/test_sse.py -q`：缺少 Hub；实现后断开/重连暴露无效连接复用 | 修正 ASGI 中间件与连接生命周期后 11 passed |
| 有界 Worker | `pytest tests/integration/test_supervisor.py -q`：缺少 runtime 模块 | 首轮 5 passed |
| App 恢复 | `pytest tests/integration/test_lifecycle.py -q`：9 failed，缺少 Supervisor、Health 和恢复 | 首轮 9 passed；扩展后 16 passed |
| 优化模式、日志安全 | `pytest tests/integration/test_hardening.py -q`：2 failed，`-O` 移除校验、Uvicorn 记录原始异常 | 2 passed，显式领域校验与 ASGI 错误边界 |
| 写入未确认停止接收 | Supervisor 专项：1 failed，创建提交未确认时仍 `accepting=true` | 7 passed；停止接收并保留持久事实 |
| 未知事件枚举隔离 | Lifecycle 专项：1 failed，去前缀处理错误接受 `ACCEPTED` | 原始枚举验证后 16 passed |
| SSE 写入背压 | `pytest tests/unit/test_stream_backpressure.py -q`：1 failed，写超时后仍有 Hub 订阅 | 1 passed；显式关闭事件迭代器 |
| Worker 运行期损坏隔离 | Supervisor 专项：1 failed，损坏 PENDING 导致后续合法 Run 无法执行 | 8 passed，损坏记录隔离后继续处理其他会话 |
| SQLite URL 边界 | Config 专项：3 failed，远程形式和两种内存 URI 未拒绝 | Config 10 passed，认证 12 passed |

补充验收：5 个 Schema 契约测试覆盖全部生命周期 Payload、状态与 Result 的冻结字段。竞态测试不使用概率性 sleep；模拟器的阶段等待和 SSE/关闭超时是被测生产行为。

以上是本开发会话实际运行的摘要，不把后续回归测试声称为首次红测。测试命令的详细终端输出保存在开发聊天中；本文件记录失败原因与对应修复，便于设计窗口复核。

## 2026-10-09 独立审查反馈修复

原始独立审查脚本（用户提供的临时目录）未经修改重跑：修复前 **3 failed**，修复后 **3 passed**。

| 修复 | 正式红测 | 绿测与约束 |
|---|---|---|
| P1：取得执行权时验证 Session 保护 | `pytest tests/integration/test_session_protection.py -q`：3 failed；已排队任务绕过 UNCERTAIN，重建 App 两种 UUID 扫描顺序均绕过隔离 | 3 passed；受保护任务保持原始 PENDING/ACCEPTED，执行器零调用；其他 Session 继续执行，资源确认后可重新提交排队 |
| P2：空库也验证核心 Schema | `pytest tests/integration/test_schema_readiness.py -q`：6 failed；三张表缺失或缺少请求/事件/Result 字节列未准确判定 Schema NOT_READY | 6 passed；认证 Health 503，Schema NOT_READY，创建拒绝；不自动修补 |

Session 门禁与创建门禁共享检查，在 `BEGIN IMMEDIATE` 事务内核验隔离集合及持久 UNCERTAIN 记录。内部 `SessionBlocked` 表达 Session 级阻断，复用冻结错误码，不增加状态、事件或 HTTP 故障参数。

Recovery 全部扫描完成后才启动 Worker，取得执行权时再次验证 Session，因此正确性不依赖有效 PENDING 和损坏 Run 的 UUID 顺序。Schema 检查使用核心 SQLAlchemy Table 的全列投影和 `LIMIT 0`，验证访问能力而不读取数据行。

修复后全量回归：105 passed；全新 Python 3.12 venv：105 passed in 19.73s，安装与 `pip check`、Ruff、空库迁移、启动及认证 Readiness 均通过。实现和回归保持未提交，等待复审。

## 批次 2

固定输入、真实文件资源与 Java 可靠导入的正式回归及红→绿记录见 [批次 2 开发交付](../../docs/review-harness-batch-2-development-handoff.md)。批次 1 的历史验收数字保留，不冒充本批证据。
