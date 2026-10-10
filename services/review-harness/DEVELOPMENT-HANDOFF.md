# 批次 1 Runtime 开发交付

- 日期：2026-10-09（Asia/Shanghai）
- 基线分支：`feature/session-close`
- 基线 HEAD：`09bb078e35bf8f56de529cdefd1faa0722f87ef5`
- 当前状态：批次1已通过独立复审并纳入后续基线；下文为2026-10-09开发交付历史，旧的“待复审/未提交”描述不代表当前状态。

## 实现清单

| 边界 | 已实现 |
|---|---|
| 工程 | Python 3.12、PEP 621、精确直接依赖与完整 `requirements.lock`、源码可编辑安装 |
| 配置 / API | 启动配置校验、App Factory、纯 ASGI Bearer 认证、安全 Problem Details、Readiness |
| SQLite | SQLAlchemy Async、foreign keys、WAL、5 秒 busy timeout、Alembic 初始迁移、UTC TEXT 往返 |
| 领域 | 不可变 Run/Failure/Event、状态—资源组合、时间/请求/终态字段校验、增量事件链核验 |
| RunStore | 10 个具名原子业务命令、同身份幂等与冲突、条件更新、原子序号、提交未知重读确认 |
| Result | 空 Envelope，Result/成功状态/终态事件同事务，原始 UTF-8 字节、SHA-256 与强 ETag |
| SSE | SQLite 有界分页、十进制 Cursor、补发、心跳、提交后 Hub、终态关闭、5 秒写超时、迭代器释放 |
| Runtime | 有界队列与调度去重集合、单 Worker、三阶段确定性模拟器、取消传播、清理确认与 UNCERTAIN |
| Recovery | PENDING 重排队，RUNNING 中断失败，CANCELLING 安全取消，损坏 Run/Session 隔离，有限关闭等待 |
| 文档 | 服务启动与验证说明、TDD 证据、项目 README/开发指南/路线图/ADR 实现状态更新 |

共享 Contract Schema、状态枚举与 Wire Format 未修改。Java、前端、Maven 与 Legacy 代码未修改。原有 `output/` 和架构可视化文件保持原状。

## 验收证据

初次交付干净 Python 3.12 venv 验收为 `96 passed in 18.56s`。独立审查随后发现 P1/P2 缺口；修复后干净 Python 3.12 venv 验收为 **105 passed in 19.73s**，原始独立审查脚本另行重跑 **3 passed**。安装、静态检查、空库迁移、服务启动与认证 Readiness 均再次通过。

| 专项 | 测试数 |
|---|---:|
| 配置 | 10 |
| 全部内部路由认证 | 12 |
| Alembic / Schema | 2 |
| RunStore 原子性、资源与提交确认 | 20 |
| HTTP 契约 | 9 |
| 真实网络 SSE | 11 |
| Supervisor / 模拟器 | 8 |
| App 重建、恢复、隔离与关闭 | 16 |
| 优化模式和异常日志安全 | 2 |
| 仓库 Schema 字段 / Payload 契约 | 5 |
| ASGI 写背压与订阅释放 | 1 |
| Session 启动门禁、正反恢复扫描顺序 | 3 |
| 缺表、缺列 Schema Readiness | 6 |
| **合计** | **105** |

已实际执行：

```text
Python 3.12 全新临时 venv
pip install -r requirements.lock
pip install --no-deps --no-build-isolation -e .
pip check                         → No broken requirements found
pytest -q                         → 105 passed
ruff check .                      → All checks passed
ruff format --check .             → 通过
alembic upgrade head              → 空库迁移成功
python -m review_harness.main      → 在临时运行目录独立启动
认证 GET /internal/v1/health       → 200 READY，acceptingRuns=true
git diff --check                  → 通过
UTF-8 / BOM / 乱码 / 尾空格检查    → 无发现
常见 Secret 签名检查              → 无发现
git diff -- contracts/schemas     → 无改动
```

完整验收入口为 `py -3.12 tests/verify_clean_environment.py`，成功输出 `CLEAN_ACCEPTANCE`。常见 Secret 签名扫描是静态检查，不声称能识别任意未知凭证。红—绿证据见 [TDD-EVIDENCE.md](TDD-EVIDENCE.md)。

## 自查修正与独立审查重点

主 Agent 对失败路径做了对抗式自查，并通过失败测试修正：优化模式移除领域校验、底层异常泄漏、提交无法确认后仍接受任务、未知原始事件枚举被接受、SSE 写超时订阅残留、运行期单 Run 损坏阻塞其他会话，以及 SQLite 内存/远程 URL 绕过配置校验。

建议设计窗口重点审查 `RunStore._write/_transition` 的提交确认、`HistoryValidator` 的事件—快照一致性、取消竞争后的资源证明，以及 Supervisor 停止接收与关闭边界。此建议不替代独立审查，本窗口没有将开发自查标记为设计批准。

### 独立审查 P1/P2 修复（2026-10-09）

- **P1 根因**：只在创建时验证 Session 保护，取得执行权时没有复核。`start_run` 现在在原写事务内复用 Session 门禁，检查隔离集合及同 Session 的持久 UNCERTAIN 记录；Supervisor 将 `SessionBlocked` 作为局部阻断，保留 PENDING/ACCEPTED 和原保护证据，不写 STARTED、不调用执行器。不同 Session 不受影响，资源确认后可重新提交排队。原身份重试仍返回已存在的事实，实际启动必须再次过门禁。
- **恢复顺序**：不改变冻结的恢复状态机。Recovery 全部扫描完成后才启动 Worker，启动事务读取扫描后的保护集合；正式回归覆盖损坏 Run 先扫描和有效 PENDING 先扫描两种顺序。
- **P2 根因**：Alembic revision 不足以证明结构完整。`schema_ready` 现在于同一只读事务中验证 revision，并对三个核心 Table 执行全列 `LIMIT 0` 查询；缺表/缺必需列即 Schema NOT_READY，认证 Health 503 并拒绝创建。不迁移、不修补数据库。
- **红—绿证据**：用户原始脚本修复前 3 failed、修复后 3 passed；新增正式回归修复前 3+6 failed、修复后 9 passed。完整记录见 [TDD-EVIDENCE.md](TDD-EVIDENCE.md)。
- **交付状态**：仍未提交、未推送；不进入下一批，等待本次修复复审。Wire Schema、状态枚举与 Migration 未修改。

## 未验证项与已知限制

- 本机 Windows + Python 3.12 已验证；Linux/macOS 与多进程部署未验证。
- 本批只有模拟执行器，Result 内容为空；真实 Snapshot、LLM、Tool、Artifact/Evidence、Memory、Skill、MCP 及 Java/前端集成不在本批范围。
- RELEASED 的恢复证明只适用于不创建外部资源的模拟器；引入真实 Tool 前必须替换。
- 多 Worker 无跨进程防护，官方启动入口固定单 Worker；没有 Redis/PostgreSQL/消息队列。
- 迁移资源保留在服务源码目录，支持源码/可编辑安装；未提供独立 Wheel 迁移资源分发。
- 数据库损坏或 Schema 不兼容保持 NOT_READY；单 Run 损坏只隔离，不自动修复、删除或重写历史。
- 契约测试直接核对当前 Schema 使用的字段、基础类型与模式，不是完整 JSON Schema 引擎。
- Java/前端回归未运行，相关代码未改变；本批验收针对独立 Python 服务。

## Git 工作区交付

未暂存、未提交、未推送。开发变更为：

```text
 M README.md
 M docs/architecture/current-architecture.md
 M docs/decisions/0009-review-harness-service-contract.md
 M docs/decisions/0015-context-artifact-and-evidence-contract.md
 M docs/decisions/README.md
 M docs/guides/local-development.md
 M docs/roadmap.md
 D services/review-harness/.gitkeep
?? services/review-harness/.gitignore
?? services/review-harness/DEVELOPMENT-HANDOFF.md
?? services/review-harness/README.md
?? services/review-harness/TDD-EVIDENCE.md
?? services/review-harness/alembic.ini
?? services/review-harness/migrations/
?? services/review-harness/pyproject.toml
?? services/review-harness/requirements.lock
?? services/review-harness/review_harness/
?? services/review-harness/tests/
```

既有未跟踪文件仍存在，未纳入本批：

```text
?? docs/coding-review-platform-overall.architecture.json
?? docs/coding-review-platform-overall.html
?? docs/coding-review-platform-overall.visual-check.1440x900.dark.png
?? docs/coding-review-platform-overall.visual-check.1440x900.light.png
?? docs/coding-review-platform-overall.visual-check.2048x1320.dark.png
?? docs/coding-review-platform-overall.visual-check.2048x1320.light.png
?? docs/coding-review-platform-overall.visual-check.html
?? docs/coding-review-platform-overall.visual-check.json
?? output/
```
