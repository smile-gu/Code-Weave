# Review Harness 批次 3 开发交付

日期：2026-10-10。基线：`b31dc62f917710a5f958f265be28d4300c44c55f`。按仓库外批次 3 冻结稿实施 3A → 3B → 3C。当前状态：B3-R01于2026-10-10 17:17:12 +08:00独立复审通过，批次3纳入基线 dc311d7；下文保留当时开发验收的历史证据。

## 实现范围

3A：新增 `0003_artifacts` 独立迁移及 Artifact 元数据表。文件保存到独立 Artifact 根，先写 staging、flush/fsync/关闭句柄，再使用同卷不覆盖 hardlink 发布 content，移除 staging，确认元数据后才返回 READY 引用。提交后响应丢失时重新读取完整记录核实。每次内容读取在同一安全句柄中验证完整大小和 SHA-256，再按 UTF-8 code point 分页。元数据不公开宿主路径。跨 Run 统一 NOT_FOUND；Cursor 以内部凭证派生 HMAC，绑定 Run、Artifact、摘要、偏移、版本。

3B：统一 ToolGateway 提供 `read_file`、`search_code`、`glob_files`、`git_diff`、`read_artifact`。身份、根目录、工具能力、调用 UUID 均由平台注入。入口严格拒绝额外字段、bool 数值、越界或超长参数；隐藏工具直接调用同样拒绝。重新验证原 Snapshot Manifest，不信任执行器可修改的字典；源码仅允许最终 REGULAR_FILE Entries，先验证当前安全句柄完整大小/摘要再返回。固定 Diff 只读取已捕获文件，不调用 Git。glob 仅匹配 Manifest 元数据；搜索为区分大小写的字面量，支持跨行匹配，严格 UTF-8，二进制/不可解码文件跳过并报告 PARTIAL。

3C：统一权限、取消、时间/扫描/输出预算、脱敏与大输出转存。必需 Artifact 发布、元数据或完整性失败终止 Run；不能以预览掩盖失败。大输出保存脱敏后的 data 与完整 sourceRefs，返回有界预览及引用；分页工具不递归转存。sourceRefs 是来源锚点，不是 Evidence。默认模拟执行器仍返回空 Result，不自动调用工具，不接真实模型。

## 配置与运行

已有数据库须显式 `alembic upgrade head` 到 `0003_artifacts`；不修改 0001/0002 历史迁移。Readiness 访问五张核心表的必需列，缺表/列拒绝接收任务，不自动修补。

新增必填 `CODE_WEAVE_REVIEW_ARTIFACT_ROOT`。Snapshot、execution、Artifact 三根必须为绝对路径且互不包含，现有祖先不能为链接/reparse point。环境示例见 [服务配置](../services/review-harness/.env.example)。

| 默认预算 | 数值 |
|---|---|
| 单 Artifact 捕获 | 8 MiB |
| 单 Run Artifact 总字节/数量 | 64 MiB / 256 |
| 工具输出及内容页整体 JSON | 32 KiB（包括 UTF-8、转义和包装） |
| 工具超时/搜索扫描 | 10 秒 / 64 MiB |
| read_file 默认/最大行数 | 200 / 1000 |
| 搜索与 glob 默认/最大结果 | 100 / 500 |

源码行以 LF/CRLF 定义，1-based；单匹配片段最多 4096 code points。扫描完整与展示截断分别标记，长行片段截断可仍为 scanComplete=true，但 coverage=PARTIAL。原始捕获超限的 Artifact 标 PARTIAL；JSON 脱敏扩张后再截断也明确改为 text/plain，不声称截断内容是合法 JSON。FULL Artifact 可以配截断预览；到末页不改变 PARTIAL 来源性质。

认证接口（前缀 `/internal/v1`）：

- `GET /review-runs/{runId}/artifacts/{artifactId}`：元数据。
- `GET /review-runs/{runId}/artifacts/{artifactId}/content?cursor=opaque`：UTF-8 分页。

二进制元数据可表达，但本批不提供二进制下载或二进制发布入口。凭证轮换后旧 Cursor 失效，可从首屏重新读取。没有面向外部的工具执行 HTTP 入口。

## 持久资源与失败边界

Artifact 不随执行副本清理；无去重、GC、跨 Run 继承。失败后 staging/content 孤儿保留、不可查询，按真实目录数量和文件字节计费，重建服务不会重置配额。启动最多检查 64 条材料、诊断窗口 10 秒；请求时完整校验始终为权威。坏材料标 CORRUPTED，不阻断其他 Run。

文件操作复用 WorkspaceManager 的有界实际 Future 注册：同 Run 最多一个文件任务，全执行根最多两个实际任务，槽位耗尽立即拒绝，不进入隐藏线程池队列。发布锁按 Artifact 根和 Run 隔离，A 的残留写线程不会阻塞 B 的发布。取消或超时后真实线程/句柄未退出，保留 RETAINED 与 FAILED+UNCERTAIN，所属 Session 门禁继续有效。只有真实资源收敛后 Supervisor 才裁决安全终态。

脱敏覆盖私钥块、Bearer 和明确 credential 赋值/JSON 字段，包括带前缀及 camelCase 凭据名和转义引号。先有界捕获再整段处理，私钥替换保留 LF 数量，身份 path/hash/UUID 保持来源引用可用。原 Snapshot 不修改。此规则可能误遮蔽以 credential 后缀命名的普通字段，也无法识别任意命名、编码或混淆的秘密；它不替代权限隔离。日志仅记录调用 UUID、白名单工具名、耗时、字节及错误码，不保存参数、源码或绝对路径。

B3-R01 修复：已识别 credential 赋值采用线性值扫描，转义引号不结束值；捕获截断或原输入未闭合时，遮蔽当前捕获范围内剩余值，不回退为首词。包括跨行和尾部反斜杠，替换保留 LF 数量。此保守策略可能连带隐藏未闭合值之后的普通文本；非凭据赋值不消费后续值，既保持原文，也不遮挡后续凭据识别。不扩大捕获上限，不修改 Snapshot 原件。

## 验收证据

| 范围 | 实际结果 |
|---|---|
| Python 全量 | 195 passed |
| 干净 Python 3.12 | 195 passed；精确锁安装、pip check、Ruff、空库迁移、真实启动及认证 Readiness 通过 |
| Java 正式 clean test | 280 tests，0 failure/error/skipped |
| Legacy 专项 clean test | 39 tests，0 failure/error/skipped；独立读取当前 XML，未混入旧正式报告 |
| 8 份 Schema | Draft 2020-12 元校验、每份正反例、生产模型契约比对通过 |
| 静态与差异 | Ruff check/format、git diff --check 通过 |

真实 Uvicorn/HTTPX 闭环验证 Probe → Supervisor → 执行副本 → Spill → 清理 → 空 Result → 认证分页 → 重启仍可读。故障回归覆盖发布碰撞、元数据失败/提交响应丢失、配额/孤儿、内容篡改、Windows junction、超时残留线程与其他 Session 继续服务、存储故障不返回引用/Result。最终补强的生命周期专项 3 项通过，涵盖坏 Artifact 重启后无关 Run 仍可成功。

红→绿记录见 [TDD 证据](../services/review-harness/TDD-EVIDENCE.md)。测试产物位于系统临时目录，不加入仓库。Windows 本机已验收；POSIX 未运行，不声称跨平台验收完成。用户已有 output 与架构可视化未改动。

复审优先证伪发布原子性/元数据确认、跨 Run 读取、预算/脱敏、真实 Future 残留与 Session 隔离。未接真实模型、Context、Evidence、MCP 或生产自动调查；SUCCEEDED 仅表示模拟底座执行完成，不表示代码审查通过。

## B3-R01 修复验收（2026-10-10，历史记录；现已复审通过）

首次新增正式回归 13 failed，复现引号值尾部泄漏；修复后扩展为 19 项边界回归。Artifact、Tool、生命周期和原始独立审查测试合计 51 passed，其中独立测试未经修改 1 passed。覆盖闭合/未闭合单双引号、空格、转义、尾部反斜杠、跨行、JSON 截断文本回退、UTF-8 中途截断，持久内容及应用服务读取均不含测试秘密，非凭据原样保留。

本轮 Python 全量 214 passed in 68.25s，干净 Python 3.12 全量 214 passed in 64.85s；安装、pip check、Ruff、空库迁移、真实启动及认证 Readiness、git diff --check 通过。结果同步于 TDD 证据及仓库外问题日志。Java/Legacy/Schema 未修改，本轮未重复运行，其上表数字仍是原开发交付证据。B3-R01 尚待独立复审，不提前关闭，不进入下一批。
