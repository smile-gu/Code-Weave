# Review Harness Runtime — 批次 1

独立 Python 3.12 服务，提供确定性模拟执行。批次 1 已实现内部 HTTP、持久 Run/Event/Result、有界单 Worker、取消、SSE 和启动恢复。真实 Snapshot、模型、Tool 与 Java/前端集成属于后续批次。

## 安装与启动

在 `services/review-harness` 执行，Python 必须是 3.12：

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.lock
.\.venv\Scripts\python -m pip install --no-deps --no-build-isolation -e .

$harnessRuntime = Join-Path $env:TEMP 'code-weave-review-harness'
New-Item -ItemType Directory -Force -Path "$harnessRuntime/snapshots" | Out-Null
$env:CODE_WEAVE_REVIEW_DATABASE_URL = "sqlite+aiosqlite:///$($harnessRuntime.Replace('\', '/'))/runs.sqlite"
$env:CODE_WEAVE_REVIEW_SNAPSHOT_ROOT = "$harnessRuntime/snapshots"
# 交互输入真实内部凭证，避免把凭证明文写入脚本或 Git。
$harnessCredential = Read-Host 'Internal Bearer Token' -AsSecureString
$env:CODE_WEAVE_REVIEW_INTERNAL_BEARER_TOKEN = [System.Net.NetworkCredential]::new('', $harnessCredential).Password

.\.venv\Scripts\python -m alembic upgrade head
.\.venv\Scripts\python -m review_harness.main
```

官方入口默认 `127.0.0.1:8091`，固定 `workers=1`，关闭访问日志。禁止 `uvicorn --workers > 1`，多进程所有权不在本批范围。Alembic 从源码目录显式执行；本批支持源码/可编辑安装，不提供脱离源码目录的独立迁移包。

数据库目录必须已存在。Snapshot Root 必须是可读目录；模拟器只校验引用格式，不读取目录内 Snapshot 或源码。数据库 Schema 缺失/不兼容、物理损坏、恢复失败时服务返回 NOT_READY，启动不会自动迁移或修补 Schema。

Schema 检查同时验证 Alembic revision 和三张核心表的全部必需列，空库也执行检查。Session 保护在创建和取得执行权的写事务中核验：同 Session 存在损坏历史或 UNCERTAIN 资源时，已排队任务也不能启动。被阻断任务保留 PENDING/ACCEPTED；确认资源安全后通过同请求重试或下一次恢复重新排队，其他 Session 继续服务。

## 配置

环境变量统一前缀 `CODE_WEAVE_REVIEW_`，启动时解析一次：

| 后缀 | 默认值 / 约束 |
|---|---|
| `HOST` | `127.0.0.1` |
| `PORT` | `8091`，1–65535 |
| `DATABASE_URL` | `sqlite+aiosqlite:///review-harness.sqlite`，文件 SQLite |
| `INTERNAL_BEARER_TOKEN` | 必填；空值和常见示例值拒绝启动 |
| `SNAPSHOT_ROOT` | 必填；Readiness 检查存在和可读 |
| `SUPERVISOR_QUEUE_CAPACITY` | 32，1–10000；不包括当前执行项 |
| `SSE_PAGE_SIZE` | 100，1–1000 |
| `SSE_HEARTBEAT_SECONDS` | 5，`0 < x <= 60` |
| `SHUTDOWN_GRACE_SECONDS` | 5，`0 < x <= 60` |

SSE 每次 ASGI 写入超时为 5 秒；心跳为非持久 Comment。所有接口要求 `Authorization: Bearer <token>`，包含 Health、SSE 和 Result。

## 接口与事实源

| 方法与路径（前缀 `/internal/v1`） | 行为 |
|---|---|
| `GET /health` | 200 READY 或 503 NOT_READY，始终使用 Health Schema |
| `POST /review-runs` | 首次 202，同请求 200，身份冲突 409 |
| `GET /review-runs/{runId}` | 持久状态与资源状态 |
| `POST /review-runs/{runId}/cancel` | 首次运行中取消 202，其他真实状态 200 |
| `GET /review-runs/{runId}/events` | SQLite 分页补发，支持 `Last-Event-ID` |
| `GET /review-runs/{runId}/result` | 成功返回不可变原始字节、强 ETag 和 SHA-256 |

创建请求遵循根目录 `contracts/schemas/review-harness-create-run-v1.schema.json`。Body 上限 64 KiB，消息上限 32 KiB UTF-8 字节，拒绝未知字段。Snapshot 使用 UUID 与 `sha256:<hex>`，不接收宿主路径。

RunStore 每个命令在 `BEGIN IMMEDIATE` 事务内执行条件状态更新、序号递增和事件插入；成功同时插入 Result。进程内写锁只减少 SQLite 写竞争，状态转换条件与唯一约束仍在数据库中。提交报错后重读 Run、完整事件链和 Result 核实；确认失败时停止接收新任务。Hub 仅提供单比特唤醒，不存事件历史。读取和恢复逐页校验事实，损坏 Run 及其 Session 隔离，合法 UNCERTAIN 只阻断相关 Session。

模拟器只有三个有界阶段，不创建外部资源，结果中的 Pass/Evidence/Finding 与 Markdown 全为空。由此，重启可把 RUNNING 转为 FAILED/RELEASED，把 CANCELLING 转为 CANCELLED/RELEASED。引入真实工具前必须替换这项资源安全证明。

## 代码组织

- `domain`：不可变 Run/Failure/Event、状态组合与事件链校验，无框架依赖。
- `application`：明确 Port、创建/取消用例、Recovery、Readiness 和 Hub。
- `infrastructure/persistence`：SQLAlchemy Async、SQLite、RunStore 事务与完整性读取。
- `runtime`：有界 Supervisor、确定性模拟器。
- `api`：认证、DTO、Problem Details、路由和有界 SSE 写入。
- `bootstrap.py`：按数据库/Schema → Recovery → Worker → 接收任务的顺序装配。

## 验证

```powershell
.\.venv\Scripts\python -m pytest tests/integration/test_store.py tests/integration/test_supervisor.py -q
.\.venv\Scripts\python -m pytest tests/integration/test_api.py tests/integration/test_sse.py tests/integration/test_lifecycle.py -q
.\.venv\Scripts\python -m pytest -q
.\.venv\Scripts\python -m ruff check .
.\.venv\Scripts\python -m ruff format --check .
py -3.12 tests/verify_clean_environment.py
git diff --check
```

`verify_clean_environment.py` 创建临时 venv，按精确锁安装，执行全量测试与静态检查，然后从空库迁移、真实启动服务并验证受认证 Readiness。它只终止自己启动的子进程，临时 venv、数据库与 Snapshot Fixture 随验证结束清理。

测试使用真实临时 SQLite、HTTPX ASGI 与 Uvicorn/HTTPX 网络客户端；竞态使用 Barrier/Event。SQL 语句与提交故障只通过 Python 依赖/数据库 I/O 边界注入，没有生产 HTTP 故障开关。Schema 契约测试直接核对仓库中的字段、基础模式和事件 Payload，并非通用 JSON Schema 验证器。

红测与交付证据见 [TDD-EVIDENCE.md](TDD-EVIDENCE.md)，本批交付检查见 [DEVELOPMENT-HANDOFF.md](DEVELOPMENT-HANDOFF.md)。
