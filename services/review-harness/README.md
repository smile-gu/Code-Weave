# Review Harness Runtime — 批次 4

独立 Python 3.12 服务，提供确定性模拟执行。批次 1 已实现内部 HTTP、持久 Run/Event/Result、有界单 Worker、取消、SSE 和启动恢复。批次 2 增加真实 Snapshot 校验、独立执行副本、持久文件资源记录与 Java HTTP/SSE 模拟接通。批次 3 增加独立 Artifact 和五个基础只读工具，默认模拟器仍不自动调查；模型、Evidence 和前端 Review 属于后续批次。

批次 4 提供显式注入的假模型 LangGraph 探针、独立 SQLite Checkpointer、完整请求测试计量/Context Manifest、闭合消息摘要压缩。默认 DeterministicExecutor 不变；没有真实模型、生产 Token 治理或 Evidence。完整约束与验收见 [批次 4 交付](../../docs/review-harness-batch-4-development-handoff.md)。

图Tool信封使用8KiB平台消费者预算，由Gateway统一Spill/缩页，完整正文或可读Artifact引用及分页Cursor保留到State/下一模型请求。默认Gateway32KiB上限不变；output_budget不是模型参数。超限不能由图自行替换为空正文成功摘要。

## 安装与启动

在 `services/review-harness` 执行，Python 必须是 3.12：

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.lock
.\.venv\Scripts\python -m pip install --no-deps --no-build-isolation -e .

$harnessRuntime = Join-Path $env:TEMP 'code-weave-review-harness'
New-Item -ItemType Directory -Force -Path "$harnessRuntime/snapshots", "$harnessRuntime/executions", "$harnessRuntime/artifacts" | Out-Null
$env:CODE_WEAVE_REVIEW_DATABASE_URL = "sqlite+aiosqlite:///$($harnessRuntime.Replace('\', '/'))/runs.sqlite"
$env:CODE_WEAVE_REVIEW_SNAPSHOT_ROOT = "$harnessRuntime/snapshots"
$env:CODE_WEAVE_REVIEW_EXECUTION_ROOT = "$harnessRuntime/executions"
$env:CODE_WEAVE_REVIEW_ARTIFACT_ROOT = "$harnessRuntime/artifacts"
# 交互输入真实内部凭证，避免把凭证明文写入脚本或 Git。
$harnessCredential = Read-Host 'Internal Bearer Token' -AsSecureString
$env:CODE_WEAVE_REVIEW_INTERNAL_BEARER_TOKEN = [System.Net.NetworkCredential]::new('', $harnessCredential).Password

.\.venv\Scripts\python -m alembic upgrade head
.\.venv\Scripts\python -m review_harness.main
```

官方入口默认 `127.0.0.1:8091`，固定 `workers=1`，关闭访问日志。禁止 `uvicorn --workers > 1`，多进程所有权不在本批范围。Alembic 从源码目录显式执行；本批支持源码/可编辑安装，不提供脱离源码目录的独立迁移包。

数据库目录必须已存在。Snapshot Root 必须可读，Execution Root 必须可读写；三个绝对根（含 Artifact Root）互不包含、拒绝链接/reparse别名，且位于真实仓库外。PENDING 不读取 Snapshot 内容；取得 RUNNING + ACTIVE 后先可靠保存执行空间意图，再校验原始 Manifest 摘要、内容并创建独立副本。数据库 Schema 缺失/不兼容、物理损坏、恢复失败时服务返回 NOT_READY，启动不会自动迁移或修补 Schema。

Schema 检查同时验证 Alembic revision 和Run/Event/Result 与 execution_workspaces 和 review_artifacts 五张表的全部必需列，空库也执行检查。Session 保护在创建和取得执行权的写事务中核验：同 Session 存在损坏历史或 UNCERTAIN 资源时，已排队任务也不能启动。被阻断任务保留 PENDING/ACCEPTED；确认资源安全后通过同请求重试或下一次恢复重新排队，其他 Session 继续服务。

## 配置

环境变量统一前缀 `CODE_WEAVE_REVIEW_`，启动时解析一次：

| 后缀 | 默认值 / 约束 |
|---|---|
| `HOST` | `127.0.0.1` |
| `PORT` | `8091`，1–65535 |
| `DATABASE_URL` | `sqlite+aiosqlite:///review-harness.sqlite`，文件 SQLite |
| `INTERNAL_BEARER_TOKEN` | 必填；空值和常见示例值拒绝启动 |
| `SNAPSHOT_ROOT` | 必填绝对路径；Readiness 检查存在和可读 |
| `ARTIFACT_ROOT` | 必填绝对路径；与另外两根互不包含，Readiness 检查可读写 |
| `ARTIFACT_MAX_BYTES` | 8 MiB，保存超限前缀时标 PARTIAL |
| `ARTIFACT_RUN_BYTES` | 64 MiB，包含在途及诊断孤儿 |
| `ARTIFACT_RUN_COUNT` | 256，包含在途及诊断孤儿 |
| `TOOL_OUTPUT_BYTES` | 32 KiB，Tool JSON/HTTP页总大小；可调低至1 KiB |
| `TOOL_TIMEOUT_SECONDS` | 10 秒 |
| `TOOL_SCAN_BYTES` | 64 MiB，搜索扫描上限 |
| `EXECUTION_ROOT` | 必填绝对路径；与 Snapshot Root 互不包含，Readiness 检查可读写 |
| `SNAPSHOT_MAX_FILES` | 20,000 |
| `SNAPSHOT_MAX_FILE_BYTES` | 64 MiB |
| `SNAPSHOT_MAX_TOTAL_BYTES` | 1 GiB |
| `SNAPSHOT_MAX_MANIFEST_BYTES` | 16 MiB |
| `SNAPSHOT_MAX_DIFF_BYTES` | 2 MiB |
| `WORKSPACE_PREPARE_SECONDS` | 120 秒 |
| `WORKSPACE_CLEANUP_SECONDS` | 30 秒 |
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
| `GET /review-runs/{runId}/artifacts/{artifactId}` | 认证读取元数据，不返回路径 |
| `GET /review-runs/{runId}/artifacts/{artifactId}/content?cursor=opaque` | 同Run UTF-8分页，每页完整哈希校验 |
| `GET /review-runs/{runId}/result` | 成功返回不可变原始字节、强 ETag 和 SHA-256 |

创建请求遵循根目录 `contracts/schemas/review-harness-create-run-v1.schema.json`。Body 上限 64 KiB，消息上限 32 KiB UTF-8 字节，拒绝未知字段。Snapshot 使用 UUID 与 `sha256:<hex>`，不接收宿主路径。

RunStore 每个命令在 `BEGIN IMMEDIATE` 事务内执行条件状态更新、序号递增和事件插入；成功同时插入 Result。进程内写锁只减少 SQLite 写竞争，状态转换条件与唯一约束仍在数据库中。提交报错后重读 Run、完整事件链和 Result 核实；确认失败时停止接收新任务。Hub 仅提供单比特唤醒，不存事件历史。读取和恢复逐页校验事实，损坏 Run 及其 Session 隔离，合法 UNCERTAIN 只阻断相关 Session。

模拟器只有三个有界阶段，结果中的 Pass/Evidence/Finding 与 Markdown 全为空，成功不代表代码审查通过。目录、复制线程和句柄现在是真实资源：后台任务未退出、owner 不符、链接替换或清理未知，必须保留 RETAINED 与 FAILED + UNCERTAIN。重启先建立 Session 隔离并验证资源归属，再可靠清理；无法归属的目录或资源行使全服务 NOT_READY，原始文件保留。

升级旧库必须显式执行 `alembic upgrade head` 到 `0004_context_artifacts`。未升级的库不会启动 Worker；旧 0001 migration 和 Run/Event/Result Wire 不变。Snapshot 不自动 GC，Run 结束只清理自己的执行副本。

## 代码组织

- `domain`：不可变 Run/Failure/Event、状态组合与事件链校验；协议模型使用 Pydantic，不依赖 Web/SQL 层。
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

批次 2 的实现、配置与复审入口见 [批次 2 开发交付](../../docs/review-harness-batch-2-development-handoff.md)。

Java 采集当前拒绝 Git 可见文件的有效 filter 属性（clean/process，包括 filter=lfs），不启动过滤脚本、不 hydrate、不清空过滤器后改变 Diff 语义。只有全局 LFS 注册而文件未应用 filter 属性时仍可使用。见批次 2 交付记录 B2-R04。


## Artifact 与只读工具

完整实现、失败路径和复审入口见 [批次 3 交付](../../docs/review-harness-batch-3-development-handoff.md)。Artifact 保存在独立持久根，执行副本删除后仍可读；无去重/GC/跨Run继承。一次Run只执行一个文件任务，同时全执行根实际Future总数最多2；忙时立即拒绝，无隐藏排队。配额按磁盘目录/字节保守计数，未确认元数据的孤儿也计入预算，不自动删除。

统一 ToolGateway 支持read_file/search_code/glob_files/git_diff/read_artifact；受信RuntimeContext、工具权限和toolCallId均由平台注入。模型参数strict且禁止身份/root字段。搜索是区分大小写的字面量；glob仅匹配Manifest Entries，不遍历磁盘；git_diff读取固定Diff不调用Git。源码和Diff先完整验证当前安全句柄的大小/哈希；二进制和链接不当作源码文本。工具返回不是Evidence。

长结果先保存READY Artifact再返回预览/引用；read_artifact/git_diff自身分页不递归Spill。JSON预算计入UTF-8、转义、信封和Cursor。FULL Artifact也可配截断预览；PARTIAL最后一页不意味着来源完整。Cursor使用内部凭证派生HMAC，绑定Run/Artifact/摘要/字节偏移/版本，轮换凭证后旧Cursor失效，可从首屏重新读取。

脱敏对私钥块、明确credential赋值、Bearer/token固定模式处理；有限捕获后整段处理，JSON按字段和值脱敏，跨块秘密不能靠分块躲过固定模式。Snapshot原件不改写。此规则无法识别所有秘密（任意自定义键、混淆、编码等可能漏检），不替代执行权限与Run隔离。普通日志/SSE/错误不保存源码、参数原文、绝对路径或凭证。

识别到credential引号值时，转义引号不结束值；缺少结束引号（原输入或捕获截断）会保守遮蔽剩余捕获内容并保留LF数量，可能连带隐藏后续普通文本。非credential赋值保持原样且不阻挡后续credential识别。不通过扩大捕获预算寻找结束引号。

Artifact文件发布使用同卷CREATE_NEW目录和不覆盖hardlink内容发布，立即移除staging链接；不与Snapshot/执行副本共享inode，文件系统不支持该原语时失败，不降级覆盖。临时或发布失败的文件保留为不可查询诊断材料并继续计费；没有活动线程不等于文件被删除。线程/句柄未停止则Workspace清理不能声明资源安全。启动最多核验64条Artifact元数据且设10秒诊断窗口；每次请求仍完整校验材料，坏Artifact不阻断其他Run。
