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

## 2026-10-10 批次 3

测试入口沿用冻结的 Artifact 应用服务、统一 ToolGateway、真实 Supervisor/Workspace 与认证 HTTP，不增加生产故障开关。按 3A → 3B → 3C 实施。

| 可核实红测 | 失败行为 | 最小修复及绿测 |
|---|---|---|
| stalled Artifact writer | 全根发布锁使 B 等待 A，A 线程尚未退出时 B 发布失败 | 按根+Run 弱引用锁隔离；A 持久 UNCERTAIN 保留，B 发布/读取通过 |
| prefixed credentials | OPENAI_API_KEY、myApiKey、INTERNAL_BEARER_TOKEN 原文返回 | 共用 credential 后缀识别及可处理转义引号的赋值模式；文本和 JSON 正反例通过 |
| JSON expansion truncation | 脱敏后超过捕获预算，PARTIAL 内容仍标 application/json | 所有实际截断明确 text/plain；回归通过 |

新增真实网络存储故障验收：必需 Spill 写入失败时 FAILED+RELEASED、ARTIFACT_WRITE_FAILED，无响应引用、无 Result。坏 Artifact 重启后仍不可读，服务 READY，无关 Session 仍 SUCCEEDED+RELEASED；测试的第二 Run 使用自己的 Snapshot 身份，不能复用绑定旧 Run 的 Manifest。

Python 全量 195 passed；干净 3.12 全量 195 passed，安装、pip check、静态检查、空库迁移、启动及认证 Readiness 通过。Java 正式 280 项、Legacy 39 项；分别 clean test，独立统计，不混旧 XML。8 份 Schema 元校验及正反例通过。最终生命周期补强专项 3 passed。实现/限制与复审入口见 [批次 3 交付](../../docs/review-harness-batch-3-development-handoff.md)。保持未提交、未推送，等待独立复审。

## B3-R01 捕获边界脱敏修复（2026-10-10）

独立复审指出捕获截断去掉 credential 结束引号后，旧正则回退为单词遮蔽，泄漏剩余秘密。沿用用户明确指定的真实 ArtifactService.publish → content 接口，新增正式回归首次 **13 failed**。修复为识别赋值后扫描引号值，转义字符跳过，缺闭合引号时遮蔽至捕获结尾（保留 LF 数量），不扩大捕获预算；非凭据赋值不消费值，既保留原文，也不吞掉后续凭据识别。

修复后正式边界回归扩展至19项，覆盖闭合/未闭合单双引号、空格、转义、尾部反斜杠、多行、JSON截断文本回退、UTF-8边界、实际持久文件与应用读取无泄漏、普通文本不改。Artifact+Tools+生命周期+原始独立测试合计 **51 passed**；原始 `D:/agentlearning/batch3-independent-audit/test_b3_redaction_boundary.py` 未修改并通过。额外8MiB直接脱敏边界探针通过，不将它计为正式pytest项。

新鲜全量 **214 passed in 68.25s**；干净Python3.12 **214 passed in 64.85s**，安装、pip check、Ruff、空库迁移、真实启动及认证Readiness通过；git diff --check通过。Java/Legacy/Schema本轮未重跑，前文数字保留为上次开发交付证据。B3-R01待独立复审，不提前关闭，未暂存、未提交、未推送。

## 批次 4：4A → 4B → 4C（2026-10-10）

基线 dc311d7，批次3及B3-R01已独立复审通过并提交。沿用冻结的 State/Checkpoint、模型请求、Compaction、Supervisor 与诊断HTTP接口进行TDD，不增加生产故障开关或真实Provider。

| 小步/问题 | 本轮实际红测 | 实现与绿测 |
|---|---|---|
| 4A 精确Checkpoint隔离 | 首条真实保存/读取/同namespace跨Run/重复启动测试因graph模型尚不存在失败 | strict State、真实AsyncSqliteSaver及JSON-only Serializer、精确ID/摘要/独立读取确认；提交响应丢失、保存前失败、不同Snapshot/同Snapshot别Run、损坏/元数据漂移拒绝 |
| 4B 最低集合与调用前诊断 | 最低输入预算拒绝调用测试因ContextEngine尚不存在失败 | 完整不可变请求整体计量、FULL Manifest、State引用提交确认、调用前重验证；最低/组合超限、未知capability/meter、Manifest失败/PARTIAL/损坏、Checkpoint不确认、模型配置/工具权限漂移均模型零调用 |
| 4C 闭合范围与事务式替换 | Tool批次闭合前缀测试因Compactor尚不存在失败 | 保留最近闭合单元、连续有界前缀、摘要独立预算/Manifest、引用校验、最终请求严格下降、FULL Summary和候选Checkpoint确认后采用 |
| 脱敏Tool JSON进入State误判 | 真Supervisor含password源码回归FAILED+RELEASED，原秘密已被Gateway遮蔽，但State纯文本二次扫描误读JSON转义 | JSON内容按字段/值比较复用现有redact_structure，明文按redact；绿测SUCCEEDED+RELEASED、秘密不进入State、安全后续文本保留；原始JSON/未闭合明文秘密注入仍拒绝 |
| 同namespace其他Run损坏误阻断 | 将A的Checkpoint typed标签改为pickle后，B的assert_fresh错误失败 | 先核对持久Run归属，不反序列化/继承其他Run损坏payload；当前Run完整性和重复启动仍拒绝，不明归属保守失败；限定SELECT返回blob/metadata大小 |
| Saver读取自动重建缺失表 | 已保存Checkpoint后删除writes，ready=false但aget_tuple经第三方setup仍成功并重建表 | 独立读取先投影必需writes列，缺表/列失败且不进入setup；读取失败后原缺表状态仍保留 |

对抗式自查额外补：候选摘要无进展/非法引用/PARTIAL/提交未知保留旧视图；连续两次压缩保留真实可读Artifact；实际Task按Run登记，取消拒不退出则RETAINED/FAILED+UNCERTAIN，同进程新Manager仍识别保护，其他Session使用同一图Executor仍成功。FakeWindowExceeded只为假协议测试，恢复使用同一尝试预算，五次实际调用分别有唯一modelCallId/Manifest，包含一次已失败调用与一次摘要，不谎称首次调用零次。

Java正式本轮clean test 280项，随后Legacy独立clean test39项，均0 failure/error/skipped、退出0；分别读取当前XML，未混旧报告。11份相关Schema Draft202012元校验及正反例通过，生产模型契约对照通过。Python最终完整验收结果见下方追加记录；前面批次的历史数字不冒充本批证据。

首次干净安装全量通过后Ruff发现开发中新增测试的导入排序，已修正并重新完整验收；不将这次未完成静态检查的运行写成成功的干净验收。实现保持未暂存、未提交、未推送，待独立复审。跨平台/真实模型/业务证据限制见[批次4交付](../../docs/review-harness-batch-4-development-handoff.md)。

最终本轮：Python全量 **257 passed in 78.79s**；干净Python3.12 **257 passed in 76.09s**，精确锁安装、pip check、Ruff check/format（83 files）、空库迁移、真实服务启动及认证Readiness全部通过，命令退出0。Java正式 **280**、Legacy **39**，均零失败/错误/跳过，退出0；11份相关Schema元校验及正反例、生产比对、UTF-8/no-BOM与git diff --check通过。新增43项正式测试，未计仓库外探针。HEAD仍dc311d7，暂存区为空；用户已有output及可视化未改动。未提交、未推送，待独立复审，不宣称真实模型或生产Token治理完成。

## B4-R01 / B4-R02 修复证据

B4-R01正式红测核对下一次模型请求：7KiB通过，10/30KiB两项失败，Tool信封无data且artifactRef=null（2 failed/1 passed）；测试辅助代码的首次import作用域错误已先修正，不将其作为产品缺陷证据。修复统一平台消费者预算到Gateway，图不再自行删正文。非分页Spill复用既有ArtifactService完整发布/确认；分页复用既有安全读取/Cursor并缩页，不递归Spill。

正式图回归14项及未改原始D:/agentlearning/batch3-independent-audit/test_b4_mid_output.py合计15 passed in24.57s。覆盖8KiB附近、中间区间、32KiB以上、search_code、两种分页工具、下一实际Request内容、实际可读转存及中等Spill故障不继续调用模型。相关Artifact/Tool/生命周期/Compaction专项69 passed in37.23s。

B4-R02仅调整测试：A entered/cancellation_seen/release事件控制真实Task，人工model_timeout=1、cleanup=0.5秒，A保护与B成功完整断言保留；生产默认预算不变。该用例在图专项、全量、干净安装分别通过，不声称旧时间敏感失败证明了生产隔离缺陷。

最终本轮Python **267 passed in108.84s**；干净Python3.12 **267 passed in108.27s**，精确锁安装/pip check/Ruff（83files）/空库迁移/真实启动/认证Readiness通过。Ruff、格式、git diff --check通过。Java/Legacy/Schema无本轮改动，未重复原280/39及元校验，其数字是上轮交付证据；生产契约测试随全量通过。新增10项正式测试，独立探针不混入267。待独立复审，未暂存、未提交、未推送，不进入批次5。


## 批次 5 首段历史记录（2026-10-10）

真实协议、计量器、Adapter、调用意图、能力协商入口均以缺失公开接口的红测开始，再逐段实现。Provider 边界用惰性 HTTPX MockTransport；持久与工具边界为真实临时 SQLite/Artifact/Saver/Workspace/Gateway。新增覆盖固定 17-token 官方合成样例、字段/工具 Schema 拒绝、原 ID 与乱序结果续接、重复 ID、Usage 漂移/缺失/损坏、重定向零重试、超限声明正文零读取、取消实际流关闭、意图提交失确认及失败零请求、恢复残留保护、模式不匹配无 Run 预留。Python 全量 296、Java 正式 282、Legacy 39 已有本轮证据；最新变更专项与最终干净环境结果见批次 5 开发记录。尚未实现 5B/5C，不作为批次完成证据。

记录时间：2026-10-10T22:14:24+08:00

## 批次 5 连续实施：5A → 5B → 5C（2026-10-11）

冻结稿已确认的协议、Runtime、存储、HTTP 和产品读取边界继续采用红→绿。缺真实启用依赖时认证创建入口拒绝且零请求/零预留；生产 App 用离线 transport 完成启动、Provider 原 ID 工具续接、收尾、2.0 原子结果及 Evidence 材料读回。

Java 2.0 校验先因新公开接口缺失失败；实现后有限 INCOMPLETE 报告、悬空引用、伪造 Markdown 和幂等导入通过。前端新报告组件先因入口缺失失败；实际 Java 路径读取、HTML/危险 URL 拒绝、材料按文本渲染通过。11 KiB 源码先被错判 INCOMPLETE；Artifact 连续读完及源码分段/尾页转存的失败回归推动覆盖进度修复，跳页不能冒充 FULL。

真实固定 Meter 的连续两次压缩保留实际 Evidence/Candidate，非法引用不替换有效视图。独立来源回归包含摘要/行坐标/归属/篡改、删除代码的物理 Diff 和私钥中间行脱敏；删除探针首次设置错误 gitMode，修正夹具为 000000，未放宽产品校验。ModelCall 保存失确认、恢复不重放、计量漂移在重建存储后仍保留门禁。

固定 Eval 四种子 × 四读取变体，实际 Runtime/Store 验证静态 Bug、null guard 反例、量化/误标静态的性能声明；每例模拟 Result commit 后丢确认，同一身份重试不创建第二结果。该脚本验证平台门禁，不代表 DeepSeek 自主审查质量。

最终独立临时根 Python 345 项通过；干净 3.12 同为 345 项，安装/pip check/迁移/启动/认证 Readiness通过。前端正式 57、Legacy 3 及两种构建通过。Java 最终新鲜完整计数见 docs/review-harness-batch-5-development-handoff.md，不混计 Legacy 或旧 XML。Ruff、格式与原仓库换行设置的 diff check 通过。

并行收口阶段曾出现 Python 329/1（执行根临时不存在）及 Java 285/1（首次 Snapshot 捕获失败）；两个原失败边界独立复跑分别 1/5 通过，尚无稳定生产复现，不宣称根因已确认。验收脚本使 pytest 临时根归属于本次独立接受环境，随后按串行矩阵收口；原断言和生产完整性校验保留。

真实 Provider 请求 0。实现保持未暂存、未提交、未推送；付费小样与统一独立复审仍是剩余交付条件。

最终收口补充（2026-10-11）：40000-token 观测仍进入持久漂移保护，不扩大输入预算；重复取消不得打断实际关闭，关闭异常保留 UNCERTAIN/执行副本/有界租约，同进程恢复与其他 Session 回归通过。捕获上限下完整读取截断 Artifact 仍 INCOMPLETE；图步逐节点检查，2 步预算只产生 1 次模型调用。Finding 至少锚定交付范围，上下文引用仍合法。四种子扩展到 24 个读取/捕获变体。

环境代理的受控红测证明本地网络验收可被操作环境代理重路由；network_client 与干净启动探针显式直连自建本地服务，未修改系统配置。最终 Python/干净环境均 345，通过；Java 正式 286/Legacy 39（各自独立新 XML）、前端 57/Legacy 3 与构建通过，静态/差异检查通过。真实请求 0，保持未提交，见最终交付记录。

## 批次 5 Evidence 专项补修（2026-10-11）

测试边界沿用已确认的 Gateway、实际生产 Provider 请求/工具信封、认证 API 与存储确认。新测试不预先给脚本模型注入源码摘要，而是复制实际工具投影和 USER_INPUT anchors。

红测复现：final 引用缺失、Diff 分页无物理/半行坐标、Artifact 无独立坐标、第二条错误证据缺少整批预校验。自查红测另确认：连续完整 Diff 被误判 PARTIAL、已脱敏 Artifact 被接受为 FULL Evidence。修正后均锁入正式回归。

生产 App 覆盖合法 JSON 的错误 hash/行号/覆盖→具体反馈→纠正→Evidence/Result；候选 codeReferences 错误→纠正→独立 VALIDATION 反例拒绝。两次纠错共同计数，持续错误停止。第二条错误时没有第一条事实发布；整批提交失确认后核对全部记录。Snapshot 篡改、存储失败、诊断写失败及取消无新增纠错调用。

诊断经认证 Artifact 内容 API 读取，提供索引、阶段、稳定原因及受控引用；不保存原始提交/未知字段/模型叙述。低页预算返回 TOOL_LIMIT，不制造资源失败；Artifact 预算和 Cursor 保留。

新鲜 Python 全量 382 通过，310.90 秒；Ruff/check format（114 文件）通过。真实请求 0；Java/干净环境最终结果见交付记录，不能混用旧真实失败记录宣称新闭环通过。


## B5-R04 补修（2026-10-11）

hunk 内前缀代码按旧/新剩余行数解析，文件头与无末尾换行标记保持不可引用。正式红测已复现空投影；新增 5 组真实 Git/多文件/多 hunk/零计数/CRLF/跨页回归。新鲜专项 56、Python 全量 387（330.58 秒）通过；Ruff/format 114 文件及差异检查通过。Java/前端/干净环境本轮未重跑，真实请求 0，保持未提交、未推送。完整记录见 docs/review-harness-batch-5-diff-parser-fix.md；待独立复审，真实闭环仍待验。


## 第五轮结构提交纠错补修（2026-10-11）

离线红测确认拒绝回复未进入纠错请求、验证隔离视图遗漏反馈、大回复仅有预览，以及反馈缺少具体校验约束。已复用有界 State/Artifact/请求计量保存脱敏回复并装配完整纠错视图；新增最多 8 条白名单 schemaViolations，约束仅从平台 Schema 提取；明确先证据确认再引用 ID 的阶段。结构化脱敏保留 JSON，不把原回复放入诊断，不放宽 Schema/引用/身份或两次纠错门禁。最终专项 40、Python 全量 395（374.49 秒）、Ruff/format 115 文件及差异检查通过。Java/前端/干净环境未重跑，额外真实请求 0；真实闭环仍未重验。详见 docs/review-harness-batch-5-submission-correction-fix.md，待独立复审，未暂存/提交/推送。


## 2026-10-12：请求回合与真实 Token 诊断

原始请求精确重建：本地 6758 / 实际 6760。恢复连续 assistant 之间的请求回合后，正式对照 6772 / 6772；未更改计量或增加容差，原漂移记录保留。2 次诊断响应仅用于计量，不作为审查事实。累计次数限制已撤销、预算提高到 8 元，手测按次预留，最小输入不含大输出填充。随后真实复验前 6 次计量一致，但第 7 次空 assistant 导致 PROVIDER_RESPONSE_INVALID；2 条 Evidence、独立验证/Result 为 0，FAILED + RELEASED。最后 Usage 未知，保留 0.088064 元预留，已向用户询问最新账单，未继续付费。生产代码全量 399（332.31 秒）；新增 8 元费用配置回归另 4 通过；Ruff/format 116 文件、差异通过。详见 docs/review-harness-batch-5-completion-turn-and-meter-diagnosis.md。未提交、未推送，真实完整闭环仍未通过。


## 2026-10-12 真实最小闭环验收补充

- 费用确认：用户提供控制台 0.20 元 / 24 请求 / 116,027 Tokens；此前 0.09 元保守合并。保留更高历史预留上界 0.475320 元；累计授权 8 元，不限制累计发送次数，单 Run 16 次门禁保持。
- 协议补充：checkedScope 必须逐字复制 scope.targetPaths 完整文件路径，不得使用方法名、目录或描述标签；没有放宽校验或自动改写模型提交。
- 新鲜正式生产请求纠错回归：先红 12 项，补充说明后 12 项通过；本次修改两文件 Ruff / format 通过，git diff --check 通过。本轮未重复全量、Java、Legacy、前端或干净环境验收。
- 真实证据目录：C:/Users/ROG/AppData/Local/Temp/code-weave-real-smoke-j5od_put。
- Run c07a7fdb-078d-415a-84d9-c185d224c005：SUCCEEDED + RELEASED；Result 2.0，correctness COMPLETED / FULL，5 Evidence，1 VERIFIED Finding，无未验证候选。8 次官方 DeepSeek 请求包含 1 次独立 VALIDATION，全部有效 Usage，所有观察到的输入估计与实际相等；输入 32,339 / 输出 4,814 Tokens。
- 只读离线核对：Result API 保存文档与数据库 canonical bytes / sha256 一致；Evidence 数据库记录与 Result 一致，认证材料副本引用、原 Snapshot 完整 hash 与物理行正文一致；Finding 引用真实 VALIDATION 调用；Run 执行目录及 staging/run 已删除。核对摘要保存在 acceptance-summary.json。
- 本轮峰值未命中价格成本上界 0.103190 元；保守累计上界 0.578510 元，已释放本轮请求预留，无未知 Usage。此值是平台成本上界，不冒称控制台实际扣费。
- 限制：只证明最小公开合成材料的 Python 真实闭环。模型把 Java 默认访问权限的方法描述为 public，属于报告措辞事实偏差；null 解引用回归由源码、Diff、用户要求支持，仍成立。独立验证指独立请求和原始材料验证，使用同一模型，并非独立供应商。大输出、压缩及 Java/UI 不由本次证明。参考 tokenizer 主站身份校验限制保留，不能宣称所有请求绝对精确。
- 本轮真实闭环可交复审；不宣称整批提交条件达成。保持未暂存、未提交、未推送，不进入下一批。


## 2026-10-12T14:16:02+08:00 批次 5：业务要求与实现事实混淆补修（离线）

用户请求中的 public 描述与源码默认权限冲突。共享调查/验证策略已区分期望行为与实现事实；独立验证可返回严格的完整 correctedCandidate，保留候选身份和所有来源，修正后的候选再经过原 Finding 门禁。核心前提被反例推翻时拒绝，未解决假设及定量声明保持原降级。Schema 严格性、Evidence 引用、单 Run 16 次预算与全局两次纠错未放宽。

新增冲突输入与反例、非法修正测试 10 项；协议专项合计 35 项通过。完整全量回归进行中，结果另行追加。Ruff 及格式 117 文件通过；差异检查通过。历史真实 Result 原 SHA 与 canonical bytes 只读核对一致，原 public 描述偏差保留；本轮无付费请求。

修复记录：D:/personal_project/coding-review-platform/docs/review-harness-batch-5-fact-correction-fix.md。模型事实识别仍属语义能力，离线替身只能证明协议和门禁，不声称真实模型新行为已验收。未重复 Java/Legacy/前端/干净环境；待设计窗口独立复审。未暂存、未提交、未推送，不进入下一批。


## 2026-10-12T14:20:14+08:00 补修最终离线验收

- Python 全量新鲜结果：410 项通过，424.96 秒；basetemp 为 C:/Users/ROG/AppData/Local/Temp/code-weave-b5-fact-correction-20261012。
- 本补修与证据协议专项：35 项通过，127.97 秒。删除重复的验证身份检查后，最终代码上新增 10 项再次通过，59.72 秒。
- Ruff 全服务通过，format 117 文件通过，git diff --check 通过。
- 历史真实 Result 原 SHA sha256:25bd13262a1d16ba869a896a4b58727506c9c8b9e553814afb7e4476d3a4ea91 与 canonical bytes 只读核对一致，原 public 描述保留。外部问题日志仅追加并验证旧字节前缀；未改不可变产物。
- 本轮零付费请求。Java、Legacy、前端、干净环境及真实模型新指令行为未重复验收。协议和来源/身份/预算门禁通过离线验收，模型是否正确识别所有事实仍需独立评估。
- 待设计窗口独立复审；未暂存、未提交、未推送，不进入下一批。


## 2026-10-12T15:18:07+08:00 批次 5 最终无需付费验收交付

最终工作区新鲜矩阵：Python 全量410/干净3.12 410；Java正式286/Legacy39，XML均failure/error/skip=0且独立保存；前端正式57/Legacy3及两构建通过；Schema/预算/上下文专项16（不与全量重复相加），Ruff/格式117文件、差异检查通过。命令、UTC起止时间、退出码和日志位于 C:/Users/ROG/AppData/Local/Temp/code-weave-b5-final-20261012-144612。

原真实Result规范字节通过Java生产导入/幂等/新Store持久读回、身份/hash/引用拒绝；21项实际探针通过。关闭Session且当前仓库不存在仍能读历史；生产React组件经生产Java控制器访问独立Python固定材料，浏览器展示草稿、范围、限制及源码/Diff/原请求，无审批按钮；内部错误Bearer被拒绝。隔离页面/控制器组合，不冒称完整部署登录或整页导航端到端认证。原Result SHA和历史public偏差保持不变。

两个真实目标已零发送预检：冲突输入初始1450Tokens，压缩目标六文件37848材料Tokens/初始1492Tokens。软水位22118/硬27648不修改；实际触发取决于模型路线，未压缩必须记未验。建议后续各1 Run×16调用，共最多32新请求，新增价格上界2.818048元；原保守上界加总3.396558元，执行前重新核价并确认本轮授权。此轮零付费，不复用旧额度自动重跑。

统一记录 docs/review-harness-batch-5-final-offline-acceptance.md。剩余真实事实冲突/压缩未验；POSIX、远端取消、总体模型质量和主站tokenizer资产核实限制保留。产品实现/正式测试未改，仅验收文档和仓库外工具。无新产品阻塞，不批准整体提交；未暂存/提交/推送，不进入批次6。


## 2026-10-12T18:18:02+08:00 最终补修回归与交付

- 最终 Runtime / 事实核对 / Evidence 协议：`pytest tests/integration/test_review_fact_correction.py tests/integration/test_real_review.py tests/integration/test_evidence_protocol.py -q --tb=short --basetemp=C:/Users/ROG/AppData/Local/Temp/code-weave-b5-final-affected-20261012`，66通过，282.93秒。
- 最终 Adapter / 真实HTTP / 费用保护：`pytest tests/integration/test_deepseek_adapter.py tests/integration/test_provider_http.py tests/integration/test_manual_paid_budget.py -q --tb=short`，42通过，13.94秒。
- 共享Schema同步与正反例：8通过，0.43秒；Ruff通过，format117文件通过，git diff --check通过。上述专项存在复用，不和旧全量相加。
- 中间扩大专项115通过（379.80秒）覆盖真实上下文、纠错反馈等；之后追加原文来源约束、候选文本限制和诊断可读性调整，最终66/42分别覆盖变更面。中间unchanged-correction测试分支漏注入导致1失败/40通过，已修正测试并在最终66项中通过，未把中间运行当最终证据。
- 产品变更仅review_harness/domain/review.py、runtime/review.py、runtime/deepseek.py；正式测试变更仅test_review_fact_correction.py、test_real_review.py、test_deepseek_adapter.py。外部Result/Java/UI协议不改，未改生产取消、资源、预算或重放规则。
- 原Result hash仍为25bd13262a1d16ba869a896a4b58727506c9c8b9e553814afb7e4476d3a4ea91；新失败事实冲突Result仍为8f32129b9bfc8556587fb94458d84e9bfc39e4fee4f94c95b4dcd04ecc013cd5，均只读核对，不修改历史偏差。
- 本轮零付费请求，未读密钥；仅离线重建请求和保留平台历史指令，不重放/解隔离。当前模型核对语义稳定性和原压缩失败根因仍未知，真实压缩成功仍未验。
- 旧Python全量410/干净410保留为补修前证据，不宣称最终代码全量。Java286/Legacy39、前端57/Legacy3及真实历史导入/展示证据保留，因外部结构和这些代码未变而不无差别重跑。整批批准仍待设计窗口独立复审。

详细定位及差异：docs/review-harness-batch-5-real-target-offline-diagnosis.md。未暂存、未提交、未推送，不进入批次6。


## 2026-10-12T18:41:34+08:00 候选来源投影与摘要规范化诊断交付

本轮零付费请求。只读证据：最新事实候选只引用2条Evidence，Run有5条，两次验证引用了额外Run记录；候选级与Run级确认范围混淆已独立定位，门禁拒绝正确。证据存于最新复验根 fact-conflict/offline-reference-scope-diagnosis.json。压缩仅能确认ProviderResponse有效后JSON/Schema规范化失败；compactionCount=0、summaryVersion=0、summaryArtifactRef=null，原摘要具体字段未知，记录 compaction/offline-summary-stage-diagnosis.json。

最小差异：实际验证请求新增候选级candidateFactSources清单，明确只用该候选Evidence、用户要求使用userQuote、counterexampleHolds语义；原来源/身份/纠错/预算门禁不变。摘要规范化新增COMPACTION_JSON/COMPACTION_SCHEMA安全阶段、最多16条白名单字段路径诊断，不存正文或值，不重试、不放宽摘要校验。

红测：生产请求缺候选来源清单；坏摘要JSON/Schema均只有旧VALIDATED_RESPONSE诊断，先失败后补修通过。新增额外Run Evidence展示但不授权，以及引用额外Run Evidence仍拒绝的两条回归。最终受影响矩阵 `pytest tests/integration/test_review_fact_correction.py tests/integration/test_real_review.py tests/integration/test_evidence_protocol.py tests/integration/test_deepseek_adapter.py tests/integration/test_provider_http.py tests/integration/test_real_context.py tests/integration/test_submission_feedback.py tests/integration/test_manual_paid_budget.py -q --tb=short --basetemp=C:/Users/ROG/AppData/Local/Temp/code-weave-b5-sourceprojection-normalize-20261012`：119通过，359.81秒。额外边界单独2通过/15.13秒、初始红转绿3通过/6.06秒均与最终矩阵重叠，不重复加总。共享Schema8通过/0.42秒；Ruff、format117文件和差异检查通过。

本次仅修改runtime/review.py、runtime/deepseek.py和test_review_fact_correction.py、test_deepseek_adapter.py；未修改外部Result、Java/UI、生产压缩策略、取消/资源/预算。旧Java/UI历史证据保留；Python全量/干净410仍为历史，不冒称新代码全量。新增投影改变实际请求Token数，不把旧真实计量对照视为新请求认证。

不能证明模型下一次一定正确引用/识别冲突；没有定位原摘要具体错误，也没有修复旧空响应根因或证明真实压缩成功。待设计窗口独立复审后再决定是否授权有诊断的真实复验。本轮不自动付费重跑，未提交、未推送、不进入批次6。详见 docs/review-harness-batch-5-real-target-offline-diagnosis.md。


## 2026-10-12T19:56:30+08:00 摘要纯文本子项最终离线交付

完整阅读33复盘、34冻结稿后仅调整摘要。先红：合法文本被旧JSON/sourceRefs协议阻断；prefix-only SourceRef在旧Summary遗漏。绿测后扩大矩阵 `pytest tests/integration/test_compaction.py tests/integration/test_real_context.py tests/integration/test_deepseek_adapter.py tests/integration/test_deepseek_meter.py tests/integration/test_provider_http.py tests/integration/test_model_calls.py tests/integration/test_real_schema_fixtures.py tests/integration/test_real_review.py -q --tb=short --basetemp=C:/Users/ROG/AppData/Local/Temp/code-weave-b5a-text-compaction-20261012`：108通过，183.52秒。末尾新增工具响应拒绝测试后，最终摘要/Adapter/Context/Meter矩阵74通过，36.78秒；之后仅新增脱敏发布测试，单独1通过，1.45秒，产品代码未再改。这些计数重叠，不相加冒称全量。

Ruff通过，format117文件通过，git diff --check通过；4个相关静态Schema与model_json_schema一致且严格结构未变，扩大矩阵含8共享正反例。没有需要重新导出的Schema字段变化，旧CompactionResponse未被共享导出，内部移除不遗漏资产。

纯文本摘要COMPACTION无tools/json_object，原Provider身份/finish/Usage/Token漂移保持；最多8192字符/16384UTF8字节，脱敏后非空；JSON样式文本不解析身份。平台来源由State/前序Summary/闭合prefix结构引用合并，Summary和保留State共用集合；超限失败，归属/hash不放宽。连续三次真实Adapter离线压缩保留Evidence/Candidate，未知ID叙事不注册来源，随后工具原ProviderID配对续接。Fake独立协议保留，不强迫生产模型生成引用。

改动范围：domain/provider.py、runtime/deepseek.py、runtime/context.py、runtime/compaction.py；测试test_compaction.py/test_real_context.py/test_deepseek_adapter.py/test_deepseek_meter.py；ADR0015/0016及交付文档同步。Evidence/Candidate/factChecks/句柄、Result2.0、Java/UI、Run事务不改。旧Summary/Result/诊断保留。

本轮零付费、未读取密钥、未暂存/提交/推送、不进入批次6。旧Python全量/干净410是历史，不认证当前代码；Java/UI外部协议未变保留已有证据。新纯文本请求模板只有离线19Tokens样例，旧JSON托管样例不认证新模板；真实压缩仍待独立复审后另行授权。最多一次探索、明确补修后最多一次确认，同原因再失败回设计窗口，不无限叠提示。模型叙事真实性/语义无损未保证。

详情 docs/review-harness-batch-5a-compaction-text-protocol.md。请求设计窗口独立复审摘要子项，不扩大Evidence句柄范围。


## 2026-10-12 兼容型 Token 预算（冻结稿40）

此项替代任何输入低估立即全局阻断的旧规则。DeepSeek 策略 deepseek-compatible-budget-v1：完整请求 E，U=max(1024,ceil(E/10))，C=E+U；C≤27648 才发送，软水位比较 C。所有真实调用目的共用策略，Run 内不按 Usage 学习或放宽。

E<A≤C 告警 INPUT_ESTIMATE_UNDERCOUNT；C<A≤27648 告警 INPUT_ESTIMATE_RESERVE_EXCEEDED。告警绑定 ModelCall 并发布认证可读 SYSTEM Artifact，保存 E/C/A/差值/版本，不复制模型正文。A>27648 或实际总量>32768 时先保存 Usage，再停止，不执行返回工具。旧 PROVIDER_TOKEN_METER_DRIFT 字节保留，但不再毒化 Readiness；坏资产、资源 UNCERTAIN 等保护保留。

真实 Context Manifest 写严格 1.1（新增 C/U/policyVersion），1.0 历史严格读取；ModelCall v1 新字段可选，历史缺省字段不补 null 重写。Result2.0 与 Java/UI 报告合同未变。没有已确认稳定结构化超窗错误码，生产恢复 DISABLED，仅严格分类接口 Mock；HTTP400/超时不自动重发。余量是工程策略，不保证误差最坏界；Tokenizer/DSML 不改。

本轮只离线，具体新鲜证据见 docs/review-harness-batch-5-compatible-token-budget.md；旧全量、Java/UI 与真实验收不能代替本轮证据。


## 2026-10-12 用户调整：取消工具累计硬上限，默认256K窗口

用户明确取消所有工具累计次数的限制，并要求提高Token预算，随后指定默认256K与接近大窗口前压缩。此授权替代冻结25/40中的32次工具及32K操作窗口；不修改其他安全边界，不进入批次6。

当前策略deepseek-compatible-budget-v2：W=262144（256×1024），O=4096，P=1024，B=257024。U仍max(1024,ceil(E/10))，C=E+U；C≤B才发送。已存在闭合可压缩历史且C>0.8B时自动压缩；80%为工程软水位，不等到耗尽窗口。边界C205619无需压缩，C205620触发；E233658+C余量可恰好放入B，E233659拒绝。摘要、调查、提交和验证共用，压缩须严格减少Token且最终预算内，平台锚点/配对/Checkpointer保护保留。

Provider档案仍引用已核对的官方1M容量来源，256K为本平台自定操作窗口，不宣称重新认证Provider最大容量。本文不把用户Codex 500K/475K的5%显示差异当作已知实现依据；本次官方配置文档无法读取，原因未确认。余量不是数学保证，超窗恢复保持DISABLED。

取消graph_max_tool_calls配置和Context的累计次数阻断；State2.0的toolCallCount与providerCallMappings去除总量数值约束，仍非负/唯一/实际计数一致。每响应最多8工具、模型调用16、图步/墙钟、Token、输出与Checkpoint字节等现有限制不变，故“不限制工具总数”不等于无限时长或无限模型请求。旧32/64配置不再支持，新启动不得继续使用旧参数。工具预算耗尽的既有分支不再从新生产入口触发，旧失败原事实保留。

预算历史兼容：旧1.0/1.1 Manifest严格读取，新Manifest1.2绑定v2及B257024；旧ModelCall缺省字段/策略v1仍按旧B27648校验，不重写原字节。ModelCall新v2数值上限同步扩大。Result2.0结构不变，累计inputTokens最大范围扩大为16×262144，Python和共享Schema同步，Java仍使用同一Schema。旧真实Result保留，无手改不可变产物。Tokenizer、Evidence、候选、factChecks不改。

真实烟测费用预留随新输入预算同步为每请求最坏0.546816元（现有峰值输入2、输出8元每百万的管理策略），16请求全量最坏8.749056元；不自动视作8元预算可承受全量最坏。本轮零API调用，下次付费必须基于最新价格、现有累计费用和逐请求预留重新核对，不重用旧1.409024元预留描述。

离线回归新增超过64/80历史工具计数仍能规范续接与持久、40K输入及Usage、真正超预算先保存Usage再拒绝、Manifest版本/Schema、256K软水位、原ID配对和取消。最终新鲜矩阵结果完成后追加；中间64K运行不是最终256K证据。Java共享合同专项5通过，预算数值更改后已重跑。保持未提交、未推送，先离线复审。


## 最终新鲜验收

最终256K版本：

- Python受影响矩阵：`python -m pytest tests/integration/test_real_context.py tests/integration/test_compatible_token_budget.py tests/integration/test_deepseek_adapter.py tests/integration/test_real_schema_fixtures.py tests/integration/test_model_calls.py tests/integration/test_compaction.py tests/integration/test_manual_paid_budget.py tests/integration/test_real_review.py tests/integration/test_real_contracts.py tests/integration/test_real_enablement.py tests/integration/test_provider_http.py tests/integration/test_deepseek_meter.py -q --tb=short`，177通过，242.55秒。
- 收尾新增硬预算恰好放入/多1Token回归后，`python -m pytest tests/integration/test_compatible_token_budget.py -q --tb=short`，30通过，4.27秒。与上组重叠，不累计报告为207。Mock初稿使用预消费的httpx.Response导致StreamConsumed，已改为ByteStream，生产代码未因此改变。
- Java：控制平面目录`../../mvnw.cmd '-Dtest=ReviewResultV2Test,ReviewRealContractTest' test`，5通过，failure/error/skip均0，5.763秒，256K共享Result Schema已打包。
- Ruff、格式110文件、`git diff --check`通过。
- 独立只读探针：旧ModelCall fixture canonical字节保持；v2接受E100000/C110000，改标v1拒绝，证明不将历史策略放宽为新窗口。

回归覆盖：原ProviderID、80个已有工具映射仍可续接、40K输入/告警/持久、超过新操作预算先存Usage后停止、最小输入三目的零发送、历史漂移App重建、旧Manifest1.0/1.1与新1.2严格读取、Schema、压缩来源/无进展/Checkpoint故障及取消、实际费用预留调整后未知Usage不重放。

没有全量Python、干净环境、Java全量/Legacy或UI重跑，不把历史矩阵冒充当前。之前真实Result与验收产物未更改；本次256K尚无付费实测，也不证明事实冲突质量或完整Finding已通过。已获得用户取消工具总数和默认256K的明确授权，但仍须设计窗口独立复审；未提交、未推送、不进入批次6。


## 2026-10-13 局部句柄协议（冻结43）

真实入口State3.0：平台s/e/c，模型n仅本批；同批新Evidence+Candidate可COMPLETE。Registry绑定Run/Snapshot且保存观察范围/原Source和UUID映射，模型用局部选择、验证c专属s原文，平台解析回既有内部UUID/FactCheck。Evidence批事务与Checkpoint独立，精确确认后才采用，失确认停止不重分配/重放。半行/未展示/转存预览/脱敏/候选专属与修正门禁保留；纯文本压缩不建映射。内部Schema及fixtures独立，外部Result2.0、Java/UI字段不变；256K与字节/集合限额不扩大。旧State2严格读取、Fake不迁移，生产无自动旧Run续跑。交付与新鲜证据见docs/review-harness-batch-5-local-handles.md；仅离线、未提交/推送、不进入批次6。


## 最终新鲜离线验收（2026-10-13）

Python服务目录，.venv Python3.12：

`python -m pytest tests/integration/test_real_schema_fixtures.py tests/integration/test_local_handles.py tests/integration/test_review_fact_correction.py tests/integration/test_real_enablement.py tests/integration/test_evidence_protocol.py tests/integration/test_evidence.py tests/integration/test_submission_feedback.py tests/integration/test_real_review.py tests/integration/test_real_context.py tests/integration/test_compaction.py tests/integration/test_model_calls.py tests/integration/test_compatible_token_budget.py tests/integration/test_provider_http.py tests/integration/test_deepseek_adapter.py -q --tb=short`

256通过，418.73秒。包含新句柄、迁移后的事实冲突、当前App及历史State2原引用门禁，类型/范围/数据完整性、预算、取消与压缩等。

- 补充fresh State3无继承及Checkpoint freshness断言后，`python -m pytest tests/integration/test_local_handles.py -q --tb=short`：26通过，54.57秒。与256有重叠，不累计282。
- 内部/公共fixture分开后，`python -m pytest tests/integration/test_real_schema_fixtures.py -q --tb=short`：12通过，0.51秒。
- Java服务目录`../../mvnw.cmd '-Dtest=ReviewResultV2Test,ReviewRealContractTest' test`：5通过，4.247秒，无failure/error/skip。公共契约仍原UUID；未修改Java白名单或UI代码。
- Ruff与格式115文件通过；`git diff --check`退出0。

中间失败如实保留：首次广矩阵233通过/18失败，均旧Mock以UUID协议驱动升级后的State3入口；现已明确legacy fixture并补当前协议独立用例。新schema反馈Mock一度误把含反馈字样的system指令按JSON解析，导致JSONDecodeError，被Adapter正确拒绝；修正仅测试解析选user消息后通过。Java一次内部fixtures混入公共枚举导致拒绝，拆分fixtures后5通过。上述中间结果不当作最终通过计数。

最终没有改预算策略/Tokenizer/终态事务/资源释放，也没有真实API或收费诊断。Python全量、干净环境、Java全量/Legacy与UI矩阵未重跑，统一最终收口再做；历史证据保留但不替代当前。交设计窗口独立复审，不批准整批提交或批次6。
