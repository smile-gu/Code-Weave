# 批次 5 最终无需付费验收

日期：2026-10-12。执行根 `C:/Users/ROG/AppData/Local/Temp/code-weave-b5-final-20261012-144612`，各命令独立 JSON 记录 UTC 开始/结束时间、工作目录、退出码和完整日志；汇总为 `matrix-summary.json`。本轮零付费请求，无生产实现修改，未暂存、未提交、未推送。

## 新鲜回归矩阵

| 项目 | 命令（对应服务目录） | 新鲜结果 | 状态 |
| --- | --- | --- | --- |
| Python 全量 | `.venv/Scripts/python.exe -m pytest -q --tb=short --basetemp <验收根>/pytest` | 410 通过，411.92 秒 | 已通过 |
| 干净 Python 3.12 | `.venv/Scripts/python.exe -m tests.verify_clean_environment` | 独立 venv 锁安装、editable 安装、pip check；410 通过，428.09 秒；迁移、启动、认证 Readiness 通过 | 已通过 |
| Java 正式 | `../../mvnw.cmd clean test` | 286；failure/error/skip 均 0 | 已通过 |
| Java Legacy | `../../mvnw.cmd -Plegacy-worktree-run-regression clean test` | 39；failure/error/skip 均 0 | 已通过 |
| 前端正式 | `npm test` | 12 文件、57 项通过 | 已通过 |
| 前端正式构建 | `npm run build` | TypeScript + Vite 成功 | 已通过 |
| 前端 Legacy | `npm run test:legacy-worktree-run` | 1 文件、3 项通过 | 已通过 |
| 前端 Legacy 构建 | `npm run build:legacy-worktree-run` | 成功 | 已通过 |
| Schema / 预算 / 真实上下文专项 | `pytest tests/integration/test_real_schema_fixtures.py tests/integration/test_manual_paid_budget.py tests/integration/test_real_context.py -q --tb=short` | 16 通过，9.68 秒；已包含于全量，不额外相加 | 已通过 |
| 静态 / 格式 | `python -m ruff check .`、`python -m ruff format --check .` | 117 文件格式通过；本地和干净环境分别通过 | 已通过 |
| 差异 | `git diff --check` | 退出 0；Git 存在已有 CRLF 提示，不是差异错误 | 已通过 |

Java 两轮都先 clean，并在后续 clean 前独立保存 `java-formal-xml` / `java-legacy-xml`，计数来自本轮 XML，不混入历史 XML。正式矩阵包含 Java 共享正反例校验；Python Schema 专项核验 Draft 2020-12 元 Schema、当前模型与生成 Schema 同步及反例拒绝。

固定离线 Eval 随 410 全量通过：nullable、guarded、quantitative、mislabelled-performance 四种种子及分页/低捕获上限等变体，详见原离线 Eval 文档。结果说明平台分级与反例门禁，不等于真实模型自主发现质量指标。

## 保存真实 Result 的 Java / UI 闭环

使用真实 Run `c07a7fdb-078d-415a-84d9-c185d224c005`。从旧 SQLite 只读连接导出原 request、Result 规范字节和持久事件完整封装；SQLite backup、Snapshot、Artifact 均复制到独立 `saved-real`。旧产物未改。

- Python 运行生产 App，使用独立数据库和材料根、临时内部认证；没有模型凭证，SIMULATED 启动仅提供已保存历史读接口。外层只允许 GET，不能创建或重跑任务；这不构成一次新的模拟/真实审查。
- `SavedRealAcceptance.java` 在仓库外编译，调用生产 ReviewHarnessClient、ReviewProtocol、ReviewFactImporter、ReviewExecutionStore、ReviewApplicationService 和 ReviewController。原 Result 2.0 严格导入、重复导入幂等、新 Store 持久读回原字节通过。
- 21 项验收断言包括错误 hash、错 runId/snapshotId、破坏 Finding 证据引用、其他 Session 和未知 Evidence 拒绝；5 条实际材料读取，错误内部 Bearer 返回 401。负例修改的是内存副本，历史文件不变。
- Session 设置为 CLOSED，指向不存在的仓库路径；历史列表、原 Result、材料读取仍通过，Coding 操作哨兵未触发，没有重采 Git/Diff。
- 浏览器通过 Vite 隔离入口加载**生产 ReviewDraftPanel**，API 请求代理到生产 Java Controller，再读取生产 Python 固定材料。没有注入 fetch 响应。Playwright 观察真实标识、COMPLETED/FULL、Snapshot、范围、限制、VERIFIED Finding、源码、Diff 和原用户输入，无审批按钮。网络记录 5 个业务 GET 均 200，只访问 Java 产品接口。截图为 `.playwright-cli/page-2026-10-12T07-00-18-233Z.png`。
- 历史 SHA `sha256:25bd13262a1d16ba869a896a4b58727506c9c8b9e553814afb7e4476d3a4ea91` 与原数据库字节、复制文件相同。原报告 public 描述仍展示，不伪造修正后的历史。

验收入口：执行根的 `serve_saved_real.py`、`SavedRealAcceptance.java`、`serve_web.mjs`；本地临时认证配置不复制到文档或 Git。`run_matrix.py` 保存最终矩阵复现入口。Java probes 编译需本轮 `java-classpath.txt` 和生产 target/classes。

**边界**：Java/UI 验证采用隔离 Spring 控制器组合和独立页面，不宣称完整生产部署、整页 Coding Session 导航或登录体系端到端验收；材料认证为现有 Harness Bearer + Session/Run/Evidence 归属检查。历史事实偏差保留，不是新事实冲突行为真实复验。

验收工具初次导出仅 payload、虚拟页面注册顺序及 React import 路径有错误，已在仓库外修复后重复验证；均不是产品缺陷，未改产品实现。初次准备脚本在 async loop 内迁移错误，改为线程执行同步 prepare 后预检通过；无网络发送。

## 两个真实模型目标（待本轮授权）

离线预检入口：执行根 `prepare_paid_plan.py`，成功产物 `paid-preparation-d0u4s8_b`。它只创建独立输入、迁移、持久 RUNNING/ACTIVE、准备/清理执行副本、构建并计量初始实际请求；MockTransport 仅作**禁止发送哨兵**，零调用、零 ModelCall，未注入成功响应，也不宣称该准备 Run 已审查成功。

### A：事实冲突修正

沿用最小公开 Java 合成输入，仍保留用户 public 描述与源码默认权限冲突。初始请求 1450 Tokens；材料 16 Tokens。运行时禁止改输入或 Result 来“补成功”。通过须有原文 Evidence、独立 VALIDATION、支持 null 问题的有效交付，Finding 的访问权限/可达性/假设与原文一致；若调查已正确识别，不人为注入错误候选，明确“冲突识别通过，真实 correctedCandidate 路径未观察”。未识别冲突如实失败，不能用离线补修通过替代。

### B：真实压缩、工具续接与交付

六个公开合成 Java 文件，每个 170 个不同算术/分支方法，带 null guard 删除的固定 Diff。完整源码量 37,848 参考 Tokens；初始实际请求 1492 Tokens，目标仍由模型自主调查，不脚本强制工具路线。

原生产硬输入预算 27,648、软触发大于 22,118 Tokens，仅在可压缩闭合历史时触发；保留 32,768 上下文、4096 输出、1024 安全余量、16 调用、32 工具、180 秒、两次纠错等生产限制。大工具结果继续由原 Gateway/Artifact/分页管理。**材料总大小超过软水位不保证实际请求历史超限**；模型提前停止或只看部分材料时记录未观察，不降水位、不预填 State、不删除关键材料制造压缩。

通过必须观察实际 `purpose=COMPACTION`、计量/Manifest/Usage、可信 Summary 发布、压缩后真实工具原 ID 续接、最终有效 Result 与资源释放；若已出现 Candidate/Evidence，检查身份与引用完整保留。若未在有锚点时压缩，标注该子目标未观察。不得从 Checkpoint 重启收费重放。

### 发送上界与预留

建议各一个独立 Run，各最多 16 次，总新增最多 32 次；没有自动重跑。按已固定价格上界，每请求最多 0.088064 元，两 Run 新上界 **2.818048 元**；叠加原保守上界 0.578510 元，累计 **3.396558 元**。执行前重新核对官方价格，并结合用户最新费用确认再预留；价格变更时不沿用数字。

本轮先不付费。用户确认目标、请求上限与预留后才发送；当前预检文件标注 NOT_AUTHORIZED_FOR_THIS_ROUND。后续生产执行只能用原 Adapter，不能沿用离线禁止发送 transport 或修改响应。零发送预检之外，不把任何旧费用授权当自动重跑许可。若 Usage 缺失或费用未知停止；发送后失败不自动再开 Run。

## 统一状态与剩余限制

| 项目 | 状态 |
| --- | --- |
| 最终无需付费回归矩阵 | 已通过 |
| 保存真实 Result 的 Java 导入 / UI 草稿 / 固定材料与历史 | 已通过（上述隔离验收范围） |
| 事实冲突补修独立离线复审 | 已通过（30 文档，历史证据） |
| 两个真实目标的输入 / 初始容量 / 预算预检 | 已通过；不是付费成功 |
| 新事实冲突真实行为 | 未验，待授权 |
| 真实压缩与压缩后工具续接 | 未验，待授权 |
| POSIX、远端取消效果、模型总体质量、tokenizer 主站资产身份核验 | 未验/已有明确限制 |
| 本轮产品代码失败 | 未发现 |
| 整批提交与下一批 | 未批准 |

本轮产品实现和正式测试文件未改；只新增验收文档与仓库外工具、临时环境。矩阵之后没有产品代码变更，不需把后续文档追加冒称代码变化。历史诊断、原 `output/` 与既有用户修改保留。待真实项目分别验收后返回设计窗口整批复审；提交/推送须明确批准。


## 2026-10-12 15:26–15:27 +08:00 授权后的两个真实目标复验

用户授权各16次、总32次；官方价格重新核对保持输入2/输出8元每百万，累计管理上界3.396558元。实际发送15次，无自动重跑。产物 C:/Users/ROG/AppData/Local/Temp/code-weave-b5-paid-targets-evz1x_f7。

事实冲突：8调用全部Usage有效且输入计量相等；SUCCEEDED/RELEASED，Result COMPLETED/FULL，独立VALIDATION实际发生。但Finding.trigger仍称public，assumptions沿用public描述，validationSummary误称无冲突，未观察有效correctedCandidate，因此质量目标失败，不可因包交付成功判通过。原Result规范字节/摘要只读核对通过，保留新失败产物。

压缩：6调查调用Usage有效且计量相等；第7个实际purpose=COMPACTION，HTTP200/640字节，RESPONSE_SCHEMA choices[0].message value_error，无有效Usage。该校验位置对应无有效正文/工具的assistant；原响应未保存，不能断言具体为空串/null/空白。Run FAILED/RELEASED，无有效Summary续接/Result，真实压缩仅发送已观察，成功目标未验。两Run执行副本均删除。

费用：事实目标峰值估计0.110168元；压缩6条有效Usage峰值估计0.111030元，最后未知保留0.088064元。原0.578510加已知新费用=0.799708，含未知预留保守上界0.887772元，不是实际扣费。费用未知已停止，无追加请求。新语义失败说明仅指令不足保证事实核对；后续离线分析验证协议应明确可审计事实核对结果，不能public替换、放宽门禁或收费盲重试。未修改生产代码；未提交/推送/进入下一批。


## 2026-10-12 最新真实调用费用确认

用户确认 DeepSeek 控制台累计 ¥0.43、47 次请求、264424 Tokens，覆盖最近两目标验收。与本地今日此前24+最小闭环8+本轮15=47次一致。账单确认解除费用未知停止条件，但不能填补最后COMPACTION缺失的逐调用Usage，也不能改变其失败/未知远端记录。保守累计预留上界0.887772元仍保留，不当作实际扣费；两个目标失败结论不变，不自动重跑。本条零请求、无实现修改。确认材料保存在验收根user-billing-confirmation.json。
