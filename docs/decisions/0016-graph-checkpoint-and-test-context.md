# ADR-0016：图状态、独立 Checkpoint 与测试模型预算

- 日期：2026-10-10
- 状态：批次 4 实施，待独立复审
- 上游：ADR-0009/0014/0015，批次 4 技术冻结稿
- 基线：dc311d77686c8bdae30e09097af7bb8c97d0cc79

## 决策

使用实际安装并锁定的 LangGraph 1.2.14、langgraph-checkpoint-sqlite 3.1.1（传递版本见 requirements.lock），支持 Python 3.12。StateGraph 最小探针为 assemble → model → tools → assemble/结束；工具只走既有 ToolGateway，默认模拟执行器不变。TypedDict 只描述图 channel；ReviewGraphState 在入口、节点输出、持久读取使用 strict/extra=forbid 的生产模型验证。

独立数据库配置为可选 checkpoint_database_url（sqlite+aiosqlite URL，绝对文件路径），未配置时图 Checkpoint 能力 DISABLED。禁止与产品库相同物理文件及链接/路径别名，禁止放在 Snapshot/execution/Artifact 根内；父目录必须存在。不将可选图能力故障伪装成默认模拟 Readiness 故障；认证 graph-capabilities 单独诊断，GET 时重新验证表/列与写访问。

使用 LangGraph 的 AsyncSqliteSaver.setup/aput/aget_tuple 真实实现，setup 管理其 checkpoints/writes 表，不用产品 Alembic 伪装独立库版本。锁定版本的表访问能力在启动验证。采用 JSON-only Serializer，未知 typed 标签拒绝，无 pickle/msgpack 对象构造和模块导入，状态与 blob 有独立字节上限。

配置映射为 thread_id=reviewThreadId、checkpoint_ns=snapshotId、checkpoint_id=平台 UUID。metadata 包含 runId、stateVersion、revision、identityDigest 和完整 stateDigest。每次读取明确 exact checkpointId，独立连接检查完整状态和元数据；不从 namespace 最新记录自动继承。保存后都确认；提交异常时同样独立核对精确内容，确认一致可接受，否则 CHECKPOINT_STATE_WRITE_UNCERTAIN 并停止调用。

图编译器不自动存储任意中间候选。节点通过上述真实 Saver 在明确的状态提交边界保存 gate snapshot，确保未确认 Compaction 不被图运行时提前采用。这些是调查状态快照，不提供调度器恢复/interrupt/resume；不使用 Saver 的最新选择或 pending writes 来重放。重复启动同 Run 的图即拒绝；同 Thread/Snapshot 的新 Run 只能从平台重新生成空调查状态，完全禁止跨 Run 自动继承。Checkpoint 不写 Run.status/resourceState/Result。

模型仅 FakeAdapter：capabilities/build_request/count_request/invoke/normalize_response 明确分离，最终 request 是不可变规范 JSON bytes。test-only-utf8-units 定义为完整测试协议 UTF-8 字节数量，包含 role/content、Tool Schema、包装及输出预留；不是生产 Token。未知模型/计量配置拒绝。Context Manifest FULL/READY 发布与 State 引用提交确认后，重新校验来源、能力、请求 digest，再调用同一 request。响应 Usage 与 estimated 通过 modelCallId 写入有界 State，不新增 ModelCall 表。

P0 来自平台策略、原 Run 用户输入（user角色）、原 Manifest 范围及身份、必需材料与未闭合调用。用户原文不在 State/诊断额外复制，只有目标摘要。仓库、工具与 Summary 均标为不可信数据；Summary 不获得 system 权限。requestDigest 仅在受控内部 Artifact 中，仍不是秘密零泄漏保证。

Compaction 选择连续且闭合的历史前缀，保留最近至少一个闭合单元。多工具调用按 ID 配对，可按实际完成顺序返回；未完成/重复/缺失结果拒绝。摘要请求同样使用完整预算和调用前 Manifest，预算不足只缩小闭合前缀，不递归摘要。摘要脱敏后保存 FULL Artifact，验证引用，重装配完整最终调查请求严格下降，再提交候选 State 并确认后采用。失败保持旧有效视图，提交未知时停止 Run。每请求最多2次尝试，每Run最多8次成功压缩；模型调用（含摘要）和图循环步默认各16。TestWindowExceeded 仅为测试边界，恢复占用同一压缩预算，每次实际调用新 Manifest；未实现真实 Provider 超窗治理。

实际模型/摘要/Checkpoint async Task 按 executionRoot+Run 注册，与文件 Future 一样，App 同进程重建不能误认为旧任务已经停止。Checkpoint 提交任务不通过取消等待包装假装回滚；模型协作取消，残留任务使执行副本 RETAINED、Run FAILED+UNCERTAIN。Run 清理只检查自己的任务，不让 A 的残留任务污染 B。全根最多2个模型/Checkpoint任务，无隐藏排队；此计数与文件线程槽位分别管理。

## 迁移与范围

产品库新增 0004_context_artifacts，只扩展 CONTEXT_MANIFEST/COMPACTION_SUMMARY kind CHECK，不修改0001–0003。旧Artifact保留，包含新kind的降级拒绝数据丢失。State的 Evidence/Candidate 引用本批明确拒绝非空；批次5有真实实体时再扩展及验收。

本批没有真实 Provider、生产 tokenizer、Evidence/Finding/Memory、Pass报告或生产图选择开关。SUCCEEDED 仍只意味着模拟基础设施完成。跨 Run SAFE_FOR_NEW_RUN 属于未来目标，本批实现零继承子集。POSIX 未运行时须明确报告。

2026-10-10复审修复：图Tool信封消费者预算8KiB由ToolGateway统一处理，平台output_budget不在模型参数Schema中。非分页超限可靠Spill，分页缩页保正文/Cursor；State保留完整信封，不用状态摘要隐藏正文丢失。默认服务32KiB上限不变。取消隔离正式测试改事件控制及1/0.5秒人工预算，生产默认预算不变，详见B4-R01/R02交付。


## 批次 5 状态与调用意图增量

State 2.0 保留 Run 内所有 Provider 原 ID 与平台 UUID 映射，即使历史被压缩也不得重用原 ID；Checkpoint JSON-only/exact ID/零重放规则不变。State 1.0 仍拒绝非空 Evidence/Candidate，2.0 保留事实源已确认的有界实体和覆盖进度。每次装配重新确认事实；连续两次压缩仍保留真实锚点，非法引用或无进展不能替换有效视图。

默认模拟，显式 REAL 选择真实非思考 DeepSeek Adapter。Context Manifest 保存固定 Meter 的完整请求预算与摘要；调查、摘要、验证和纠错共同受 16 次调用约束。结构提交按实际 Schema/指令计量和检查软/硬水位；不可压缩的验证材料不静默删除。图步默认 64/最大 160，Run 墙钟默认 180 秒，单次 HTTP 45 秒。0005_model_calls 保存 PREPARED/DISPATCHING/RETURNED/LOCAL_CLOSED 本地事实，提交失确认独立核验；重启不重发，实际残留 Task 未退出时不能写安全资源终态。远端结果未知不等同本地资源未释放；Checkpoint 不裁决 Run 终态。新鲜离线证据见批次 5 开发记录，真实小样另行授权。

更新日期：2026-10-11（批次 5 离线实施）

收尾校正：调查、工具和提交节点分别计图步，在进入节点前执行预算门禁。HTTP 调用的实际
收尾不被重复取消打断；缺少关闭确认时保留未知资源槽位与执行副本，同进程重建不能清除保护。
资源槽位按 Run 租约合并实际 Task 与未知关闭记录，最多 2 个，不用假 Task 或隐藏队列。


## 2026-10-12 批次 5A 摘要协议局部收敛（冻结稿34）

此项显式替代旧摘要模型输出summaryText/referencedArtifactIds/sourceRefs的要求。真实COMPACTION仅纯文本、无tools、无json_object；模型负责叙事，平台负责Summary结构来源。既有“摘要带来源”指持久Summary产物，不要求模型重新生成UUID/hash/来源对象。INVESTIGATION/VALIDATION及Evidence/Candidate/factChecks不变。

平台从进入压缩前已确认State、前序有效Summary与闭合prefix结构引用去重合并，Summary和压缩后State共用受信集合。保留来源归属/hash、FULL发布、完整请求计量、Token严格下降、Checkpoint确认、取消与预算门禁；来源集合超限明确失败，不删锚点求成功。正文中的未知ID或JSON样式文本不注册来源或权限。

正文脱敏后须非空，最多8192字符/16384 UTF-8字节；截断、工具调用、Usage/漂移异常仍拒绝，不自动重试/换模型。FakeAdapter协议独立，不要求真实子项返回模型引用。持久Summary/State及外部Result2.0结构不变，旧产物不改。细节与新鲜离线矩阵见 docs/review-harness-batch-5a-compaction-text-protocol.md；新模板尚无付费验收，旧JSON样例不认证新请求。Evidence/Candidate句柄不在本项冻结范围。


## 2026-10-12 兼容型 Token 预算（冻结稿40）

此项替代任何输入低估立即全局阻断的旧规则。DeepSeek 策略 deepseek-compatible-budget-v1：完整请求 E，U=max(1024,ceil(E/10))，C=E+U；C≤27648 才发送，软水位比较 C。所有真实调用目的共用策略，Run 内不按 Usage 学习或放宽。

E<A≤C 告警 INPUT_ESTIMATE_UNDERCOUNT；C<A≤27648 告警 INPUT_ESTIMATE_RESERVE_EXCEEDED。告警绑定 ModelCall 并发布认证可读 SYSTEM Artifact，保存 E/C/A/差值/版本，不复制模型正文。A>27648 或实际总量>32768 时先保存 Usage，再停止，不执行返回工具。旧 PROVIDER_TOKEN_METER_DRIFT 字节保留，但不再毒化 Readiness；坏资产、资源 UNCERTAIN 等保护保留。

真实 Context Manifest 写严格 1.1（新增 C/U/policyVersion），1.0 历史严格读取；ModelCall v1 新字段可选，历史缺省字段不补 null 重写。Result2.0 与 Java/UI 报告合同未变。没有已确认稳定结构化超窗错误码，生产恢复 DISABLED，仅严格分类接口 Mock；HTTP400/超时不自动重发。余量是工程策略，不保证误差最坏界；Tokenizer/DSML 不改。

本轮只离线，具体新鲜证据见 docs/review-harness-batch-5-compatible-token-budget.md；旧全量、Java/UI 与真实验收不能代替本轮证据。


## 2026-10-12 用户授权工具预算拓宽

用户明确要求拓宽工具预算并再次尝试。新增graph_max_tool_calls配置，默认32，严格范围1–64；本次独立验收显式64。真实State2.0计数和Provider映射最大64并同步共享Schema；身份唯一、配对和实际计数一致性仍严格校验。运行Context按配置在返回后拒绝超限批次，不执行超限工具。其他32约束（候选、分页进度）不扩大；模型16次、Token/费用/取消/资源保护不变。不是无上限工具调用，也未修复已有工具预算耗尽INCOMPLETE收尾缺口。

新鲜离线：test_real_context.py与test_real_schema_fixtures.py共26通过，28.66秒。覆盖31+2在32阻断/64放行、63+2在64阻断、Usage超预算仍拒绝，以及原ProviderID续接和Schema一致性。Ruff、109文件格式和git diff --check通过。Python全量/干净环境未重跑；旧26以外矩阵是历史证据，State2.0 Schema变化需设计窗口复审，不直接批准整批。

真实确认单目标一次最多16请求，官方价格核对；新增最坏预留1.409024元，先前保守费用2.093008元，累计管理上界3.502032元，低于用户授权8元。产物根C:/Users/ROG/AppData/Local/Temp/code-weave-b5-tools64-confirmation-3qf1sqtr/compaction。结果待完成后追加。未提交、未推送、不进入批次6。


## 2026-10-12 用户调整：取消工具累计硬上限，默认256K窗口

用户明确取消所有工具累计次数的限制，并要求提高Token预算，随后指定默认256K与接近大窗口前压缩。此授权替代冻结25/40中的32次工具及32K操作窗口；不修改其他安全边界，不进入批次6。

当前策略deepseek-compatible-budget-v2：W=262144（256×1024），O=4096，P=1024，B=257024。U仍max(1024,ceil(E/10))，C=E+U；C≤B才发送。已存在闭合可压缩历史且C>0.8B时自动压缩；80%为工程软水位，不等到耗尽窗口。边界C205619无需压缩，C205620触发；E233658+C余量可恰好放入B，E233659拒绝。摘要、调查、提交和验证共用，压缩须严格减少Token且最终预算内，平台锚点/配对/Checkpointer保护保留。

Provider档案仍引用已核对的官方1M容量来源，256K为本平台自定操作窗口，不宣称重新认证Provider最大容量。本文不把用户Codex 500K/475K的5%显示差异当作已知实现依据；本次官方配置文档无法读取，原因未确认。余量不是数学保证，超窗恢复保持DISABLED。

取消graph_max_tool_calls配置和Context的累计次数阻断；State2.0的toolCallCount与providerCallMappings去除总量数值约束，仍非负/唯一/实际计数一致。每响应最多8工具、模型调用16、图步/墙钟、Token、输出与Checkpoint字节等现有限制不变，故“不限制工具总数”不等于无限时长或无限模型请求。旧32/64配置不再支持，新启动不得继续使用旧参数。工具预算耗尽的既有分支不再从新生产入口触发，旧失败原事实保留。

预算历史兼容：旧1.0/1.1 Manifest严格读取，新Manifest1.2绑定v2及B257024；旧ModelCall缺省字段/策略v1仍按旧B27648校验，不重写原字节。ModelCall新v2数值上限同步扩大。Result2.0结构不变，累计inputTokens最大范围扩大为16×262144，Python和共享Schema同步，Java仍使用同一Schema。旧真实Result保留，无手改不可变产物。Tokenizer、Evidence、候选、factChecks不改。

真实烟测费用预留随新输入预算同步为每请求最坏0.546816元（现有峰值输入2、输出8元每百万的管理策略），16请求全量最坏8.749056元；不自动视作8元预算可承受全量最坏。本轮零API调用，下次付费必须基于最新价格、现有累计费用和逐请求预留重新核对，不重用旧1.409024元预留描述。

离线回归新增超过64/80历史工具计数仍能规范续接与持久、40K输入及Usage、真正超预算先保存Usage再拒绝、Manifest版本/Schema、256K软水位、原ID配对和取消。最终新鲜矩阵结果完成后追加；中间64K运行不是最终256K证据。Java共享合同专项5通过，预算数值更改后已重跑。保持未提交、未推送，先离线复审。


## 2026-10-13 局部句柄协议（冻结43）

真实入口State3.0：平台s/e/c，模型n仅本批；同批新Evidence+Candidate可COMPLETE。Registry绑定Run/Snapshot且保存观察范围/原Source和UUID映射，模型用局部选择、验证c专属s原文，平台解析回既有内部UUID/FactCheck。Evidence批事务与Checkpoint独立，精确确认后才采用，失确认停止不重分配/重放。半行/未展示/转存预览/脱敏/候选专属与修正门禁保留；纯文本压缩不建映射。内部Schema及fixtures独立，外部Result2.0、Java/UI字段不变；256K与字节/集合限额不扩大。旧State2严格读取、Fake不迁移，生产无自动旧Run续跑。交付与新鲜证据见docs/review-harness-batch-5-local-handles.md；仅离线、未提交/推送、不进入批次6。
