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
