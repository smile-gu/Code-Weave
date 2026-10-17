# Review Harness 批次 2 开发交付

- 基线：`6374adf4c8470938046b2f60256c1f42cc9ba975`，`feature/session-close`。
- 状态：开发完成，等待独立复审；未提交、未推送。
- 范围：固定输入和可靠文件执行底座；模拟成功不代表代码审查通过。

## 实现

| 切片 | 交付 |
|---|---|
| 2A | 累计范围 Preview/确认；全量 tracked 与非 ignored untracked 捕获；原始 Diff；稳定 UTF-8 Manifest；来源和目的完整复核；不覆盖原子发布；不可变产品身份记录 |
| 2B | 原始 Manifest 摘要与严格结构/路径/内容/预算校验；先 RUNNING + ACTIVE，再提交执行空间意图；独立复制；线程完成证据；取消、污染、清理和两遍恢复；显式 0002_workspaces 迁移 |
| 2C | 固定请求与同 runId 重试；有界 HTTP/SSE；事件先存再推进 Cursor；终态/Result 对账；原始 Result 导入；共享 Coding/基线/Review/Close 门禁和仓库所有权恢复 |

复用现有 SessionOperationLockManager、GitChangeCollector、v3 指纹、Coding 空闲安全检查、Java 文件事实与 Python RunStore 事务。新增的是资源专用命令和 Review 产品导入记录，没有通用工作流框架或第二套 Agent Loop。

## 本机配置

Java 与 Python 同机运行，使用同一凭证与绝对目录。先创建两个互不包含的根，均放在真实仓库之外。

| 环境变量 | 含义 |
|---|---|
| `CODE_WEAVE_REVIEW_SNAPSHOT_ROOT` | Java 发布、Python 只读的 Snapshot 根 |
| `CODE_WEAVE_REVIEW_EXECUTION_ROOT` | Python 每 Run 独立副本根 |
| `CODE_WEAVE_REVIEW_INTERNAL_BEARER_TOKEN` | 两服务共享的内部凭证；不要写进 Git |
| `CODE_WEAVE_REVIEW_HARNESS_BASE_URL` | Java 访问地址，默认 http://127.0.0.1:8091 |
| `CODE_WEAVE_REVIEW_DATABASE_URL` | Python 文件 SQLite URL |

Java 未显式配置目录时由 `code-weave.runtime-root` 派生 `review-snapshots` 与 `review-workspaces`。Python 两根必填；启动前显式执行 `alembic upgrade head`。旧 0001 数据库未升级时 NOT_READY，启动不自动迁移。没有 Token 时旧 Coding 可以启动，Review 创建在预留前被拒绝。

默认预算：20,000 文件、单文件 64 MiB、内容 1 GiB、Manifest 16 MiB、Diff 2 MiB、捕获/准备 120 秒、清理 30 秒。Java 属性见 ReviewProperties，Python 环境变量见服务 README。任意超限整次失败。

## Java API

前缀 `/api/v1/coding-sessions/{sessionId}`：

| 方法与路径 | 行为 |
|---|---|
| GET `/review-scope-preview` | 返回基线、HEAD、分支、v3 指纹及累计变化 |
| POST `/review-runs` | 确认范围、持久预留、捕获和异步模拟提交；202 |
| GET `/review-runs?limit=50` | 有界历史，limit 1–200 |
| GET `/review-runs/{runId}` | 当前持久产品投影；校验 Session 归属 |
| POST `/review-runs/{runId}/cancel` | 先持久取消意图，再请求协调；202 |

创建 Body：`expectedActiveBaselineCommit`、`expectedHeadCommit`、`expectedBranch`、`expectedWorkspaceFingerprint`、非空 `targetPaths`、`input.message`。Body 64 KiB、消息 32 KiB UTF-8；拒绝未知字段。target 去重排序，excluded 由服务端补集生成。创建响应丢失时查询现有记录，客户端不能自动重复创建新身份。

Java wire 含 `simulationOnly=true`，不暴露宿主目录、原始请求消息或内部凭证。内部 Run/Event/Result/Health Wire 保持批次 1 契约。

## 故障保护

PENDING 不读 Snapshot 内容、不建目录。准备意图无法确认时不创建副本。复制线程未退出、owner 不匹配、junction 替换、删除失败均不能确认 RELEASED。清理失败优先，保留原始失败码为有界内部诊断。

重启不恢复执行：先建立 Session 隔离，再清理可靠归属资源。无法归属的目录/资源行阻断全局 Readiness且保留文件；关联 Run 的损坏隔离该 Session。已发布 Snapshot 不自动删除。Java PREPARING 恢复不重新捕获；核实固定发布身份并补偿产品记录后才结束未发送准备。

POST 接受后响应丢失、404、网络不可达、无内存句柄都不能证明未接受。协调始终使用原 runId/请求字节。事件缺口或重复冲突不投影；原始事件已存但 Cursor 失败可重放。旧响应不覆盖后到取消意图或回退 Cursor。成功必须校验空 Result Envelope、身份、Descriptor、原始摘要与强 ETag，持久保存完整事实后才解除门禁。

## TDD 与验收入口

测试边界沿冻结稿：真实 Git 捕获、真实 Snapshot/SQLite 的准备与恢复、Java 产品导入、真实 HTTP/SSE 两服务闭环。竞争用 Event/Barrier，不依赖概率性 sleep。

本轮新增红→绿证据：无法归属执行目录原先 READY；损坏已发布 Manifest 原先释放 PREPARING；矛盾本地失败记录原先绕过 Guard。均有正式回归覆盖。此前文本链接与捕获测试证据见 SnapshotCaptureTest；批次 1 原有 Session/Schema 修复继续覆盖。

重点正式测试：SnapshotCaptureTest、SnapshotPublicationStoreTest、ReviewImportTest、ReviewReliabilityTest、ReviewGateTest、ReviewRecoveryTest、ReviewContractFixtureTest、ReviewSimulationIntegrationTest，以及 Python test_workspaces/test_workspace_recovery/test_schema_readiness/test_session_protection。

共享静态 Fixture 在 `contracts/fixtures/review-snapshot-v1`，Java 核验 canonical 原始字节与二进制摘要，Python通过真实准备/复核/清理消费；真实集成另验证 Java动态发布能被 Python 接受。

```powershell
.\mvnw.cmd -pl services/control-plane -am test
.\mvnw.cmd -pl services/control-plane -am -Plegacy-worktree-run-regression test
cd services/review-harness
.venv/Scripts/python -m pytest -q
.venv/Scripts/python tests/verify_clean_environment.py
```

## 边界与复审注意

本机 Windows junction 用例实际执行通过，无 skipped。POSIX 权限设置与真实叶子 symlink 代码具备对应处理，但本机 Windows 未提供 Linux执行证据；不能把平台条件测试的跳过说成通过。

普通复制不是安全沙箱，不启动模型、Maven、Analyzer、shell Tool、MCP；不实现前端 Review、真实报告审批、Snapshot GC、多 Worker。可信操作者控制受管根，不承诺抵抗恶意宿主持续替换文件系统路径。

保留用户已有 output/ 与架构可视化，个人知识库问题日志不纳入 Git。独立审查尚未执行，本交付中的通过仅指开发窗口本机验收。

## 最终开发验收证据

- 完成时间：2026-10-09 19:00（Asia/Shanghai）。
- 统计更正：上一轮正式 Java 实际运行 273 项；此前 390 项混入 117 项旧 XML，撤回该统计。本轮使用 clean test 后重新统计。
- Legacy 专项：39 项，0 failure、0 error、0 skipped；Profile 命令退出 0。按本次报告时间筛选，排除 target 中旧包报告，不把历史 XML 重复计数。
- Python：上一轮全量与干净 Python 3.12 均为 149 passed（2026-10-09）；本轮取消测试改用持久意图提交边界同步，149 passed in 42.91s。
- 干净环境：精确锁安装、editable 安装、pip check、Ruff、空库迁移、真实启动和认证 Readiness 全通过。0001→0002 保留历史回归属于上述全量测试。
- 真实两服务闭环包含 Java 动态 Snapshot、两次独立执行，以及接受 POST 后丢失响应的第三次执行；三次均使用空模拟结果，副本清理后才解除门禁。
- git diff --check、任务文件 UTF-8/BOM、生产实现中的绝对机器路径和常见凭证模式检查通过；0001 migration与既有 Run/Event/Result Wire Schema无差异。
- Java 新增 Review代码使用 AOSP Java格式化；Python Ruff通过。前端未变，未扩大前端实现或执行前端验收。
- HEAD保持6374adf，暂存区为空。output/与已有可视化未改动。

## 第二轮独立复审修复

复审提出的残留线程背压、Inspector fsmonitor、历史删除路径前缀误报已分别以红测复现并修复。文件任务容量现在按执行根全局限制，Review Git 子进程复用只读策略，Manifest 物理冲突与历史变化分层校验。


## B2-R04 内容过滤器执行边界修复

2026-10-10：clean/process 两条正式红测已复现。ReadOnlyGit 在可能转换内容的 status/diff 命令执行前，用不转换内容的 ls-files/check-attr 检查 Git 可见路径的有效 filter 属性，包括仓库、info/global attributes 与 index 回退语义。发现非 unspecified/unset 属性，明确返回 REPOSITORY_FILTER_UNSUPPORTED；探测失败或超出有界输出/时间预算则 REPOSITORY_FILTER_CHECK_FAILED，不继续业务命令。

该策略不会清空 clean/process 配置或改变 Diff 语义。即使只配置了 filter 名称但当前缺少可执行命令，也保守拒绝；后续需要支持过滤器应单独设计。全局注册 LFS但当前仓库文件未应用filter属性时可采集；当前路径使用filter=lfs则拒绝，包括已存在的LFS pointer，既不hydrate，也不静默按关闭LFS后的Diff发布Snapshot。普通无过滤器文件继续按原始字节捕获。

正式回归从生产 ReadOnlyGit diff、Inspector、Review Preview和启动入口覆盖clean/process零调用、零启动预留，并覆盖LFS明确拒绝。原fsmonitor/external diff/textconv探针与index字节不变继续验证。属性可变性受原有来源复核与可信宿主边界约束，不宣称对恶意宿主的持续竞态隔离。

本轮还将旧复制取消测试的概率性sleep替换为资源意图提交后的确定性取消，避免负载影响测试裁决；生产Python逻辑未改变。

验收修正：共享只读策略额外关闭 diff.autoRefreshIndex，避免 Git 采集自行刷新 index；Preview/启动与拒绝路径均核对调用前后 index 字节。2026-10-10 干净 Python 3.12：149 passed in 41.27s，安装、pip check、迁移、真实启动及认证 Readiness、Ruff 全通过。

2026-10-10 B2-R04 最终默认 Java clean test：276 项，0 failure/error/skipped，命令退出 0；Python全量149 passed in 42.91s、干净环境149 passed in 41.27s。276为删除旧target后本次生成的47份报告之和，不能与Legacy报告混合统计。

2026-10-10 14:36 +08:00：Legacy专项单独 clean test，39项，0 failure/error/skipped，退出0。git diff --check通过，暂存区为空，HEAD仍为6374adf；未提交、未推送。


## B2-R04 保留字 driver 消歧修复（2026-10-10）

check-attr输出unset/unspecified既可能是属性状态，也可能是实际driver字符串，不能仅按字符串判安全。正式六组driver用例中，新增unset/unspecified × clean/process四组修复前失败，证明绕过；修复后全部拒绝且脚本零调用。

当前额外读取Git有效配置的键名（config --null --name-only --list，包括include和global，不输出命令或凭证值）。若存在filter.unset/filter.unspecified的clean或process键，则该同名输出无法证明安全，保守拒绝。即使实际属性是-filter/未指定，存在同名driver配置时也可能保守拒绝；这是明确兼容限制，不通过执行程序猜测属性语义。配置读取失败仍fail closed。

原普通driver和LFS策略不变。真实Review Preview/启动入口亦验证六种组合拒绝、零预留、零脚本调用及index不变。此次只修改Java策略与回归，Python生产代码未改。

本轮验收：默认Java clean test 280项，0 failure/error/skipped；过滤器专项8项。Python全量149 passed in 39.78s；干净Python3.12 149 passed in 42.84s，安装、迁移、启动、认证Readiness、Ruff通过。正常关闭回归改用生产5秒宽限期（原50毫秒在并行负载下不足），生产保护逻辑未改变。

- 本轮Legacy独立clean test：39项，零失败/错误/跳过；diff检查通过，未暂存、未提交、未推送，R04待独立复审。
