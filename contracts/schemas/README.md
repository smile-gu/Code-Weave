# Contract Schemas

该目录保存 Code Weave 跨进程或跨服务的版本化机器契约。Schema 描述计划接口不代表对应服务已经实现；实现状态以项目架构文档和 ADR 为准。

## Review Harness v1

| Schema | 用途 |
|---|---|
| `review-harness-common-v1.schema.json` | UUID、SHA-256、时间、状态、Failure 和 Result Descriptor |
| `review-harness-create-run-v1.schema.json` | `POST /internal/v1/review-runs` 请求 |
| `review-harness-run-status-v1.schema.json` | 创建、查询和取消接口复用的 Run 状态响应 |
| `review-harness-event-v1.schema.json` | Python → Java 的持久 Review Run 事件信封 |
| `review-harness-health-v1.schema.json` | Readiness 响应 |
| `review-harness-result-v1.schema.json` | 不可变 Review Result Envelope |
| `review-harness-problem-v1.schema.json` | RFC 9457 Problem Details 扩展 |
| `review-snapshot-manifest-v1.schema.json` | 批次 2 不可变 Snapshot Manifest，批次 2 已实现 |

字段语义、HTTP 状态、事务和恢复规则以 ADR-0009 为准；Snapshot 身份以 ADR-0014 为准；Result 内部 Evidence/Finding 演进以 ADR-0015 为准。

JSON Schema 无法表达或不适合表达的规则由应用层和契约测试负责，包括：

- 请求和字符串的 UTF-8 字节上限；
- `input.message` 至少包含一个非空白字符；
- Result `byteSize` 等于 Markdown 的 UTF-8 字节数；
- 时间先后关系；
- Result/Run/Snapshot 身份完全一致；
- 事件序号连续性和数据库事务原子性。
- Snapshot 路径无空段/尾斜杠、可移植设备名约束、大小写/NFC 冲突、目录前缀冲突、列表排序及范围划分；Schema 的路径模式只做基础格式筛查。
- Snapshot 内容集合、摘要、字节数、文件类型/mode、相对真实文件系统和 Run/Session 身份一致性。

## 批次 3

review-artifact-metadata-v1、review-artifact-content-page-v1、review-tool-envelope-v1与五种review-tool-*-parameters-v1来自生产Pydantic严格模型，测试核对文件与模型生成结果一致。正反例在contracts/fixtures/review-tools-v1/examples.json；字节预算、文件身份/哈希、Cursor签名、权限和JSON中的状态一致性仍由应用层验证。

## 批次 4

review-graph-state-v1、review-context-manifest-v1、review-compaction-summary-v1来自生产严格模型，正反例见contracts/fixtures/review-context-v1/examples.json。Artifact metadata新增本批实际消费的CONTEXT_MANIFEST/COMPACTION_SUMMARY kind。模型接口没有公共HTTP端点；这些Schema用于内部状态及诊断Artifact，不提供恢复或继承权限。Tool配对、来源哈希/归属、连续范围、请求预算/摘要进展、字节上限与提交确认由应用层重复验证。test-only-utf8-units不是生产Token计量；Evidence/Candidate非空引用本批拒绝。


## 批次 5 协议

新增 review-provider-request/response/call-mapping-v1、review-model-call-v1、review-execution-contract-v1、review-capabilities-v1、review-graph-state-v2、review-harness-result-v2。Schema 均自包含，正反例在 contracts/fixtures/review-real-v1/examples.json；Python 通过 Draft 2020-12 元校验与生产 DTO，Java 打包相同资源离线校验。JSON Schema 约束结构；应用门禁继续验证配对、归属、真实坐标/来源、跨引用、usage、确定性 Markdown 和原子持久。共有 resultDescriptor 接受 1.0/2.0，但导入版本必须与持久创建合同一致。旧 Result 1.0 严格为模拟空包。

State 2.0 保存事实锚点、Provider 映射及有界连续读取进度；Context Manifest/Summary v1 扩展有界 Evidence/Candidate ID，模拟 State v1 仍拒绝非空锚点。导出使用 `python -m tests.export_real_contracts`；生产不生成或自动修复 Schema。真实入口显式启用，缺依赖或持久计量漂移时拒绝任务；离线测试不代表真实模型质量已验收。

更新日期：2026-10-11（批次 5 离线实施）

Provider response Schema 描述 Adapter 输出的规范合同。实际 DeepSeek 响应中已观察到
`tool_calls[].index` 和 `usage.prompt_tokens_details.cached_tokens`；Adapter 在接收边界
严格校验后转换：索引只校验有界顺序，不替代原始 Provider ID；缓存明细映射到规范
hit/miss 计数，并核对总量与已有计数。未知字段、索引异常、明细格式或计数矛盾仍拒绝。
这些响应元数据不会混入下一次请求、State 或修改平台合同 Schema。

Evidence 专项补修：Artifact ContentPage v1 增加可选严格 coordinates（物理 LF 行号及半行状态）；
坐标针对存储后的 Artifact，不是原源码。旧样例无此字段仍合法，生产填入实际坐标。
工具 data.evidenceReferences 和 Diff pageCoordinates 为受信来源投影；SourceRefs、Evidence、
Run/State/Result 的既有身份及摘要约束保持。诊断使用既有 SYSTEM TOOL_OUTPUT，不扩建事实表。


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


## 批次6A1增量（协议基础，运行尚未启用）

新增自包含严格Schema：review-plan-v1、review-pass-result-v3、review-execution-contract-v2、review-capabilities-v2、review-harness-create-run-v1.1、review-harness-result-v3。生成入口为Python服务目录`python -m tests.export_multipass_contracts`；正反例独立位于contracts/fixtures/review-multipass-v1/examples.json，旧fixtures与Schema不改。

executionBudget单位全部秒，冻结300/2000/10调用与120/1800/10800/600时限；Plan选择非空/唯一/固定顺序，无Python缺省选择。maxAmount为正的人民币十进制定点字符串（最多6位小数、12位整数，适配微元int64管理表示），金额摘要为整数微元；不接受float/指数/零/负数。授权时间UTC微秒，价格ID/digest只证明引用形状，价格资料有效性须6A2核实，不将fixture占位价格当生产认证。

Schema无法单独证明跨实体身份/时间/用量/谱系/权限，Python与Java模型追加同一业务门禁。旧Result2.0读端拒绝3.0，不以单Pass约束松绑模拟新版本。1.1当前明确NOT_READY并拒绝创建，待费用与执行器完整接通才开放；不凭计划2000授权真实测试。


## 批次6A2费用合同

新增review-price-profile-v1、review-price-profile-view-v1、review-model-call-v2及内部review-fee-budget-v1/review-call-reservation-v1；严格DTO导出入口为服务目录python -m tests.export_fee_contracts。独立正反例位于contracts/fixtures/review-fee-v1。金额微元整数、价格定点字符串；旧Schema及fixtures不改。Profile来自操作员核实的文件，fixture不代表真实价格。ModelCall2.0绑定Pass及新费用账本；历史v1严格读取。费用READY不开放HTTP1.1，多Pass仍NOT_READY。交付及新鲜测试见docs/review-harness-batch-6a2-fee-ledger-handoff.md。
