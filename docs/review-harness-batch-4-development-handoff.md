# Review Harness 批次 4 开发交付

- 日期：2026-10-10（Asia/Shanghai）
- 基线：dc311d77686c8bdae30e09097af7bb8c97d0cc79，feature/session-close
- 范围：按批次 4 冻结稿 4A → 4B → 4C 实施；未暂存、未提交、未推送，待独立复审。

## 交付行为

4A：严格版本化 ReviewGraphState、LangGraph 最小探针、独立 AsyncSqliteSaver。State 只保存有界消息、身份、摘要/材料来源、Tool 配对、预算计数与 Usage；不保存 Run 终态、客户端、Path、Future 或完整用户原文。TypedDict 的输入/节点输出/存储读取均经过运行时验证。Tool Call ID 由平台在响应规范化时重新生成，配对支持一批多个 Call 及结果按完成顺序到达。

4B：FakeAdapter 的 capabilities/build_request/count_request/invoke/normalize_response 边界；不可变最终 Request、完整请求计量、调用前 FULL/READY Context Manifest、Checkpoint 引用确认及调用前重校验。P0 是平台策略、原 Run 用户输入（user身份）、重新验证的原 Manifest 范围、调查身份和必要材料；State 只存目标/范围摘要。仓库/工具/Summary 作为明确不可信块，不成为系统策略。

4C：连续闭合前缀的有界压缩，保留最近闭合单元；摘要请求本身有预算/Manifest，无递归摘要。Summary 脱敏、合法引用、FULL Artifact、最终完整调查请求计量严格下降、候选 Checkpoint 提交确认后才替换有效视图。失败不修改传入有效视图；底层提交不确定则停止 Run。材料引用保留且可读取，Summary 不能产生 Evidence/Candidate，非空业务证据引用本批拒绝。

默认 DeterministicExecutor 仍运行原三阶段并产空 Result，不自动启用图。GraphProbeExecutor 仅通过既有 Executor 注入验收，没有生产 Provider 或图选择开关。图结束不等于 SUCCEEDED，仍由 Supervisor 校验执行副本、收敛资源并由 Store 裁决终态。

## 配置、兼容与诊断

实际安装锁：LangGraph 1.2.14、langgraph-checkpoint-sqlite 3.1.1，传递依赖全部固定在 requirements.lock，未包含 editable 项目远程入口；干净 Python 3.12 验证。产品库显式升级到 0004_context_artifacts，只扩展 CONTEXT_MANIFEST/COMPACTION_SUMMARY 类型 CHECK，旧记录保留；0001–0003不变。含新类型的 downgrade 拒绝破坏材料。

可选 CODE_WEAVE_REVIEW_CHECKPOINT_DATABASE_URL 指定独立文件 SQLite。父目录需存在，绝对路径，不得等于/通过 hardlink 或路径别名指向产品 DB，不得位于 Snapshot、execution、Artifact 根；拒绝现有链接/reparse。默认未配置为 DISABLED。配置错误在 Settings 拒绝；物理不可访问则图 Checkpoint NOT_READY，默认模拟能力与图能力分开诊断。

认证 `GET /internal/v1/graph-capabilities` 返回 checkpoint（DISABLED/READY/NOT_READY）、model=TEST_ONLY、productionGraphEnabled=false。GET 重新检查独立表/列及写访问，不用默认 `/health` READY 宣称图/真实模型可用。没有模型调用/Checkpoint恢复 HTTP 接口，诊断不返回文件路径。

预算默认：图循环步16、模型调用16（含摘要和失败实际尝试）、每请求压缩尝试2、每Run成功压缩8、模型等待10秒；可调到更小。State整体256KiB、消息16KiB/最多128条、每批Tool Call最多8、State材料锚点最多64、Checkpoint JSON512KiB。原Artifact/文件预算继续有效。

计量器 test-only-utf8-units v1 定义为该假协议最终规范请求的UTF-8字节数，包含所有角色块、工具Schema、包装与输出预留。inputBudget=contextWindow-reservedOutput-protocolSafetyMargin。它不是实际Provider Token计量，不允许未知模型/计量标识。TestWindowExceeded 仅用于测试有界恢复：已有可验证压缩进展时重试，并占同一尝试预算，每次实际尝试使用新modelCallId/Manifest；没有真实Provider超窗治理。

## 保存与恢复边界

Saver 真实使用 setup/aput/aget_tuple，其 setup 管理 checkpoints/writes，不冒充产品 Alembic。thread_id=reviewThreadId，checkpoint_ns=snapshotId，checkpoint_id=平台UUID；元数据校验Run、State版本、revision、identityDigest、stateDigest。采用JSON-only受限Serializer，无pickle fallback或模块对象构造。精确ID读取，不使用最新namespace选择；每次保存正常返回或抛错都通过独立连接确认精确状态与引用。

LangGraph图节点显式通过真实Saver提交gate snapshot，编译器不自动提交未验证Compaction候选；详见ADR-0016。它不是可恢复调度器，本批不支持interrupt/resume、重启模型/工具重放或跨Run自动继承。同Thread/Snapshot新Run也从平台空状态创建，旧Run重复启动拒绝。Checkpoint的当前视图不是产品Run/资源/Result的事实源。

模型/摘要/Checkpoint实际Task按executionRoot+Run登记，共享同进程注册表防止App重建误认退出；全根最多2个，单Run最多1个，无隐藏排队。模型协作取消；Checkpoint提交不取消等待包装来伪造回滚。任务未退出则执行副本RETAINED、FAILED+UNCERTAIN并保护所属Session，A的任务不影响B清理。文件Future仍用原登记与清理。进程真实重启不续跑图，沿用产品遗留Run恢复规则。

异常保留CONTEXT_/COMPACTION_/CHECKPOINT_精确失败码；资源清理未知优先UNCERTAIN，原错误只记录安全诊断。State引用保存失败会停止调用链；Manifest失败且尚未invoke时调用零次，响应后Checkpoint失败则诚实记录已实际调用，不能声称零次。摘要Artifact提交后候选失败不删除材料。

## 验收与复审入口

正式回归覆盖：同Thread不同Snapshot及同Snapshot不同Run隔离、精确Checkpoint确认/提交响应丢失/保存前故障、类型注入/损坏拒绝、不恢复最新状态；最低集合与组合角色/中文Emoji/Schema/包装超预算、未知meter/窗口、Manifest失败/PARTIAL/损坏、引用Checkpoint失败/能力漂移时调用零次；连续两次压缩、闭合Tool批次、非法引用/无进展/摘要截断/候选保存失败保留旧视图；真实Supervisor工具Spill、假超窗恢复调用计数、残留模型Task与同进程Manager重建、其他Session继续成功。

Java正式280项、Legacy39项，均零failure/error/skipped，独立clean test及XML统计；11份相关Schema Draft202012元校验、正反例及生产模型对照通过。最终Python全量257 passed in 78.79s；干净环境结果见本文件后续验收段和TDD-EVIDENCE。测试日志位于系统TEMP，不纳入Git。

本机为Windows，POSIX未实测。Artifact固定模式脱敏局限沿用批次3；请求digest不是秘密零泄漏保证。真实Provider/tokenizer、Evidence/Candidate业务锚点、Context质量与真实审查均未实现，不以空引用或假协议数字宣称完成。

复审优先证伪：独立文件隔离及受限反序列化、提交精确确认、请求计量/Manifest/实际invoke一致性、Summary配对/引用/进展、同Run取消后残留Task保护与其他Run隔离。源码入口：domain/graph.py、infrastructure/checkpoints.py、runtime/context.py、runtime/compaction.py、runtime/graph.py、runtime/model_adapter.py；决策见ADR-0016。

开发自查红→绿另覆盖两项：已脱敏Tool JSON的State校验采用结构字段/值，避免把JSON转义当源码语法误判，原始秘密注入仍拒绝；同Thread/Snapshot其他Run的损坏payload不被反序列化/继承，也不误阻断fresh Run，当前Run损坏和重复启动仍拒绝。无法确认归属的记录保守失败，不能将其忽略后续跑同namespace。

第三方Saver读取会自动setup，额外红→绿验证删除writes后不得靠查询重建表：先检查必需表/列，缺表读取失败、NOT_READY且缺失保持。启动仍使用所选实现的原生setup初始化独立库，与产品库Alembic分开。

## 首次开发交付验收（复审前历史）

| 本轮范围 | 实际结果 |
|---|---|
| Python全量 | 257 passed in 78.79s；新增43项正式测试 |
| 干净Python3.12 | 257 passed in 76.09s；精确锁安装、pip check、Ruff、空库迁移、真实启动、认证Readiness全部通过 |
| Java正式/Legacy | 280/39项，分别clean test，0 failure/error/skipped，退出0 |
| 协议 | 11份相关Schema Draft202012元校验、正反例、生产模型对照通过 |
| 静态与差异 | Ruff check/format（83 files）、UTF-8/no-BOM、git diff --check通过 |

基线HEAD dc311d7未变，暂存区为空。开发问题日志仅追加且验证原字节保留。代码未提交、未推送；用户原有output/架构可视化未操作。交回统一独立复审，本批不进入批次5。

## B4-R01 / B4-R02 修复交付（2026-10-10，待复审）

B4-R01：删除图节点超过8KiB即丢正文的分支。图以平台专用output_budget=8192调用ToolGateway，Gateway按min(服务上限,消费者预算)处理完整信封：非分页结果超限先可靠保存Artifact，再返回预览、完整来源引用的Artifact及coverage；分页read_artifact按实际外层信封开销缩页，git_diff按消费者预算缩页，保留text/nextCursor/hasMore且不递归Spill。图完整保存信封；若Gateway违反约定则明确失败，不降格为空正文成功摘要。默认Gateway32KiB上限与模型参数Schema不变，模型不能传入此平台预算。

真实Supervisor回归覆盖7/8/10/16/30/33KiB源码、既有60KiB大输出及search_code中等结果。下一次实际FakeAdapter Request中的Tool信封与持久State一致，正文完整或有同Run可读取Artifact；转存内容及完整sourceRefs验证。分页中文/Emoji/引号/反斜杠/换行按模型实际请求的Cursor逐页继续，重组精确原文；没有新增分页Spill Artifact。必需中等结果Spill存储失败时FAILED+RELEASED、ARTIFACT_WRITE_FAILED，模型仅调用首次，不能继续用空摘要成功。

B4-R02：测试增加cancellation_seen事件，明确确认A实际Task捕获取消后仍等待release；人工model/cleanup预算改为1/0.5秒，仅修改测试，不修改生产默认值。A保持FAILED+UNCERTAIN/RETAINED、同进程Manager重建仍拒绝释放、同Session阻断；A未release时B通过同一Executor成功完成的完整断言保留。不把旧极短测试预算失败解释成已证实的生产隔离缺陷。

| 本轮修复范围 | 新鲜结果 |
|---|---|
| Python全量 | 267 passed in 108.84s |
| 干净Python3.12 | 267 passed in 108.27s；安装、pip check、Ruff、空库迁移、真实启动和认证Readiness通过 |
| 图回归及原始独立探针 | 15 passed；14项正式图回归+未修改的B4-R01原探针1项 |
| 相关Artifact/Tool/Compaction专项 | 69 passed |
| 静态/差异 | Ruff check/format、git diff --check通过 |

本轮没有Java/Legacy或Schema改动，Java280/Legacy39及Schema元校验未重跑，仍属于首次交付证据；生产契约比对随Python全量通过。新增10项正式回归，原独立探针不计入267。实现未暂存、未提交、未推送；B4-R01/R02待独立复审，不进入批次5。
