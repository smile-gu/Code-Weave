# 批次 5 开发交付记录

基线：`feature/session-close`，`e3621b4b12a90d008e098116040bc2d8920e3ebe`。
冻结依据：仓库外文档 25；计量来源为文档 22/24；ADR-0009/0014/0015/0016。
当前 5A→5B→5C 的实现与离线验收已完成；剩余真实小样授权及统一独立复审。
真实付费小样尚未授权、未执行；批次整体仍待该项及统一独立复审，不能提交。

## 5A：真实 Adapter 与调用可靠性

默认 SIMULATED/1.0，真实模式显式 REAL/2.0，两端在预留前协商。
Python 使用本地 Secret、独立 Checkpointer、固定资产与版本化能力声明；缺依赖即 NOT_READY。
固定官方 HTTPS `/chat/completions`、deepseek-flash、非流式、thinking disabled。
调查 tools auto，摘要/验证/结构提交不发 tools；JSON mode 带 Schema、格式指令及样例。
HTTP 单次有界读取，无隐式重试/重定向；API key 不进入 Java、State、Artifact 或日志。

本地 V4.1 参考编码与 Tokenizer 同字节验证后加载，固定 revision/hash/license；不改系统工具链。
平台窗口 32768、输出 4096、余量 1024、输入预算 27648；不宣称是 Provider 最大窗口。
完整不可变请求计量→FULL Manifest→Checkpoint/ModelCall 确认→能力/取消重查→同字节发送。
输入低估持久记录 PROVIDER_TOKEN_METER_DRIFT，重建 App 仍阻断真实能力，不自动校正。
六个托管样例及镜像来源局限留在能力档案；有限样例一致不等于全域认证。

0005_model_calls 保存 PREPARED/DISPATCHING/RETURNED/LOCAL_CLOSED；提交失确认独立重读精确记录。
原 Provider tool ID 与平台 UUID 分离，保留完整映射，乱序按 ID 配对，同 Run 不重用。
实际 Task/连接退出才确认释放；残留继续 UNCERTAIN，远端 outcome unknown 独立记录。
重启只处理遗留本地事实，不收费重放，不从 Checkpoint 续跑。
模型总调用 16、工具每次 8/总 32、纠错 2，摘要和验证共同计数。
实际 Usage 的有界记账整数与发送预算分开；实际输入 40000 仍先持久记录再触发漂移保护，
不能因超过操作窗口而仅报协议错误。此调整不扩大 27648 的发送输入预算。
图步默认 64/最大 160、Run 墙钟 180 秒、单次 HTTP 45 秒，所有输入输出有明确限额。
图步在每个调查/工具/提交节点进入前检查，与模型调用计数独立。
重复取消不打断已进入的 HTTP 收尾；关闭抛错或被打断时保留有界未知资源槽位与执行副本，
不将 coroutine.done 当作释放确认。同进程重建仍保留保护，其他 Session 在剩余槽位内可完成。

## 5B：自主 correctness 与 Evidence

LangGraph 调查→工具→后续调查/结构提交；模型自主决定工具路线和停止点，没有必调工具清单。
现有五工具只经 ToolGateway 访问固定输入；平台身份不能由模型覆盖。
0006_evidence 保存不可变 CODE/DOMAIN_CONTEXT 来源事实；Schema/可移植路径、坐标、真实文件摘要均验证。
全量 base blob 未存储，base 明确拒绝；删除代码使用固定 Diff 的物理 hunk 行，不能引用不存在的 final。
TOOL Artifact 需归属/类型/完整性一致；模型叙事、摘要、Manifest 不能循环充当代码 Evidence。
先整文件脱敏再选行；源码/Artifact 片段 16 KiB、用户输入 32 KiB，超限失败。

平台分配 Candidate ID，独立验证重新读取原材料；反例成立拒绝，必要假设未确认不能 VERIFIED。
运行/性能量化无生产者最多 RISK；即使模型误标 STATIC_CORRECTNESS，性能量化文本仍降级。
连续两次真实计量摘要保留 Evidence/Candidate；非法引用、失败或无进展不替换有效视图。
结构提交按含 Schema 的实际请求检查软/硬水位，不静默删除验证材料。

工具/State 共用 8 KiB 消费预算；非分页超限可靠转存，Artifact 分页保留正文/Cursor。
源码分段或可信 Artifact 连续读完才计 FULL；转存字节必须与完整工具材料摘要一致，
读完一个因捕获上限截断的 Artifact 不等于读完源码。跳页、残余内容和脱敏不冒充完整覆盖。
固定 Diff 的 Cursor 链一旦断开，后续尾页不能伪造完整检查。
COMPLETED 仅针对明确有限范围；预算耗尽且已有可核查调查可交 INCOMPLETE，未验证候选不发布。

## 5C：结果与只读产品闭环

Executor 返回候选包，Supervisor 验证执行副本并确认资源收尾，再由 Store 注入身份/时间。
同一事务保存规范原始 Result 2.0、摘要、SUCCEEDED 和事件，保留取消竞争规则与失确认重读。
Evidence 副本逐项等于事实表；调用列表/usage/验证用途/Artifact 引用必须吻合。
Markdown 由结构化结果确定性生成，UTF-8 最多 64 KiB；总 Result 最多 1 MiB。
Java 校验共享 Schema、原始 hash/ETag、身份/时间、跨引用和逐字 Markdown，然后幂等导入。
Finding 至少有一个代码锚点属于交付 checkedScope；上下文引用仍允许来自其他固定材料。
实际 Finding 涉及的确认目标由平台并入检查范围，未证明该文件完整覆盖时保留 INCOMPLETE。
导入重试不重跑模型；历史版本以持久请求为准，旧模拟 1.0 仍是严格空包。

Java 结果 GET 只读可靠导入历史，不重采 Diff、不调度模型；材料 GET 先查 Session/Run 和结果引用，
再代理 Python 认证、完整性校验与脱敏读取。前端仅 Java 请求，展示模式、范围、覆盖、Finding、
材料和限制；禁原始 HTML、危险 URL 与外部图片。没有审批/编辑/Handoff。

## 离线 Eval 与边界

种子：`services/review-harness/tests/fixtures/review-eval-v1.json`。
nullable 预期一项 VERIFIED；guarded 预期零 Finding；quantitative 和 mislabelled-performance 各预期一项 RISK。
每个种子分别用小文件/11 KiB 填充、整页/分段读取及低捕获上限执行，共 24 条实际 Runtime/Store 回归。
低捕获上限的 8 条必须交付 INCOMPLETE，而不是宣称完整读取；Finding 分级依赖真实 Evidence。
它们使用脚本 Provider、实际固定文件/SQLite/Artifact/Checkpointer；不是 DeepSeek 误报漏报率证明。
生产 App 的认证启动→Provider ID→工具续接→清理→2.0 结果→Evidence 材料读取另有完整回归。
实际 socket 假 Server 验证 HTTP 请求与计量字节、原 ID 续接、无重试。

## 验收记录

最终新鲜记录：2026-10-11 15:38 +08:00。

| 矩阵 | 命令（对应目录） | 结果 |
|---|---|---|
| Python | `.venv/Scripts/python -m pytest -q --basetemp <本次独立临时根>` | 345 通过，285.56 秒 |
| 干净 Python 3.12 | `.venv/Scripts/python tests/verify_clean_environment.py` | 345 通过，299.12 秒；安装、pip check、显式迁移、服务启动、认证 Readiness通过 |
| Java 正式 | `mvnw.cmd -pl services/control-plane -am clean test -q` | 单轮新 XML 286，通过；失败/错误/跳过均 0 |
| Java Legacy | `mvnw.cmd -pl services/control-plane -am -Plegacy-worktree-run-regression clean test -q` | 独立新 XML 39，通过；失败/错误/跳过均 0 |
| 前端正式 | `npm test -- --run`；`npm run build` | 57 通过，构建通过 |
| 前端 Legacy | `npm run test:legacy-worktree-run`；`npm run build:legacy-worktree-run` | 3 通过，构建通过 |
| 静态/协议 | Ruff check/format（110 文件）、pip check、Schema 元校验/引用/正反例、`git diff --check` | 通过；生产模型与静态 Schema 一致 |

前端构建仍提示主 chunk 大于 500 kB；没有为本批扩大 UI 架构重构。
本地 socket 验收显式禁用环境代理，并有受控失效代理回归，始终访问本次启动的本地服务。
此前一次 337/7 的 502 失败涉及该验收客户端路径；修正隔离后全量和干净环境通过，系统代理未修改。
前一轮 296/282 等计数属于 5A 首段历史，不混入本轮最终统计。
本轮曾有 305 通过/2 失败：Context Manifest/Summary 的静态 Schema 落后于严格模型，已重新导出。
11 KiB 源码完整 Artifact 分页、尾页转存回归先失败，随后修正覆盖进度并通过。
新增删除代码探针首次使用错误 gitMode，被 Snapshot 验证拒绝；修正为冻结的 000000，未放宽生产验证。
自查红测另复现并修正：超操作窗口 Usage 丢失漂移保护、重复取消打断关闭、关闭异常被错误写为
RELEASED、截断 Artifact 被计为整份源码、图步只计调查节点、Finding 没有交付范围锚点。
所有修复通过原公开边界回归；未知 HTTP 资源占用原有有界 Runtime 槽位，无新队列或假 Task。
收口过程中 Python 临时根缺失、Java Snapshot 首次捕获及 Maven 子进程清理曾间歇失败；
原失败边界独立复跑和无仓库修改的真实进程探针通过，未确定稳定根因，保留记录供独立复审。
验收脚本已使用本次独立环境拥有的 pytest 临时根；生产校验和基线测试断言未放宽。

本轮真实 Provider 请求 0，未读取或交接真实密钥。POSIX 未验。
未暂存、未提交、未推送；保留用户原有 output/旧可视化。

## 待授权真实小样

仅使用上述公开合成 Java/说明文本，不上传项目源码；目的地固定官方 DeepSeek HTTPS。
建议总共最多 8 次请求，含调查/摘要/验证/纠错，每次最多 4096 输出 Token；独立临时库/目录。
人民币 1 元为建议管理预算，执行前核验适用价格与保守最大 Token 成本；费用无法确认即停止。
不复用设计窗口的计量对照授权；取消不能保证远端停止或零计费。
这项完成后仍交统一独立复审，未获许可不提交或进入下一批。

### 真实小样启动准备（2026-10-11）

用户已要求开始上述小样。新增 `services/review-harness/tests/manual_real_smoke.py`，
在 Harness 目录用 `.venv/Scripts/python -m tests.manual_real_smoke --execute` 启动一次。
凭证通过交互式 getpass 输入，禁止非交互回退，不进入命令行、环境配置、文件或日志。
仅一个合成 Run，含约 11 KiB 公共填充源码；复用正式 Snapshot fixture 与生产 App/Adapter，
不注入 Provider transport 或 Executor。独立临时 SQLite/Checkpoint/材料目录保留以供复审。
该脚本只验证 Python 生产接通，不宣称覆盖 Java/UI 或证明真实审查质量。

运行前读取官方中文价格页并核对当前价格表，不可确认或变更则停止。
2026-10-11 核对 deepseek-flash 高峰缓存未命中输入 2 元/百万、输出 8 元/百万；
8 × (27648 × 2 + 4096 × 8) / 1000000 = 0.704512 元。
这是本地上限下的管理预留；真实 Usage 漂移会阻断继续调用，取消仍不能保证零计费。
费用账单未确认时标明未知，不自动重跑。摘要/多工具续接未观察到时必须保留未验结论。

新增脚本本地准备、官方价格门禁、生产模拟生命周期（SUCCEEDED + RELEASED，模型记录 0）、
Ruff/format 均通过；真实请求仍 0。最新修改仅涉及手动验收脚本及交付记录，
此前正式回归计数不增加、不冒充重新执行。等待用户本机隐藏输入密钥并运行一次，
再读取输出目录核验；未提交、未推送。

### 首次真实调用失败与诊断准备（2026-10-11）

用户本机目录 `C:/Users/ROG/AppData/Local/Temp/code-weave-real-smoke-63f70ba7`：
1 个 ModelCall，失败码 PROVIDER_RESPONSE_INVALID，未取得有效 Usage，LOCAL_CLOSED，
Run FAILED + RELEASED，无 Result。首次脚本未保存响应字段诊断，无法追溯确切兼容性原因。
用户随后查控制台反馈：请求一次、1347 Tokens、消费金额小于 0.01 元。
这一反馈单独记录为 user-billing-confirmation.json，不改写本地 Usage 或声称获得 Token 分项。

Adapter 新增有界安全诊断：HTTP 状态、处理阶段、固定白名单字段位置和错误类别，
不记录响应正文、值、异常消息或未知字段名；响应拒绝规则保持不变。
离线红测复现诊断缺失后修正；Adapter/真实 HTTP/生产启用/ModelCall 回归 21 通过，
生产 App + 非法响应 + 手动脚本诊断落盘离线探针通过，Ruff/format 通过。
真实故障尚未定位，不能宣称修复或小样验收通过；本轮 Agent 未发送付费请求。

新增 `--execute --diagnose`，只允许 1 次调用并停止，价格记录与该调用上限一致；
官方页面再次核对，单次保守预留 0.088064 元。该调用计入原总数最多 8 次，
首轮已消耗 1 次；待用户运行诊断后继续核验，不自动重复或进入下一批。

### 第二轮真实诊断与接收修复（2026-10-11）

真实诊断目录 `C:/Users/ROG/AppData/Local/Temp/code-weave-real-smoke-2896lwf4`。
HTTP 200、821 字节，安全诊断明确拒绝 `choices[0].message.tool_calls[0/1].index`
及 `usage.prompt_tokens_details`，均为 extra_forbidden；ModelCall 未得到有效 Usage，
Run FAILED + RELEASED，无 Result。用户控制台累计反馈：两轮合计 2 次请求、2696 Tokens、
总消费小于 0.01 元；另存 user-billing-confirmation.json，不覆盖平台事实或推断 Token 分项。

Adapter 接收边界现在严格校验并转换这些观察到的元数据，平台规范 Schema 保持不变。
工具 index 为严格整数 0–7 且等于响应数组位置，不替代原 Provider ID、不进入续接请求；
cached_tokens 为严格非负整数，不得超过 prompt_tokens，须与已有 cache hit/miss 一致。
仅有该明细时转换为规范 hit/miss 计数；未知字段、思考内容、非法索引、矛盾记账仍拒绝。
原字节限制、计量漂移保护、取消/清理和无重试策略保留。

正式红测先复现 PROVIDER_RESPONSE_INVALID；新增两工具原 ID 乱序续接、非法索引、
异常/矛盾缓存明细和缓存投影回归；安全诊断仍证明未知字段名与响应值不落盘。
相关回归 47 通过；生产 App 与真实 socket 假服务也加入上述响应元数据。
手动脚本新增 --max-calls；后续只允许 --execute --max-calls 6，计入原最多 8 次总额度，
不重放之前的 Run。官方中文价再核对，6 次保守预留 0.528384 元，加已知累计费用仍小于 1 元。
目前只完成离线接收修复，真实小样尚未通过；Agent 未新增付费请求，未提交或推送。

该修复后的新鲜 Python 全量回归：364 通过，298.99 秒，使用本轮独立拥有的临时根。
Ruff/format 通过；Java/前端/干净环境本轮未重跑，沿用先前证据并明确区分。

### Agent 真实执行与当前阻塞（2026-10-11）

详见 `docs/review-harness-batch-5-real-acceptance.md`。用户授权由 Agent 本机隐藏输入
临时凭证，后明确提高累计发送上限到 16 次，仍限总管理预算 1 元。
两轮 Agent 执行分别 5/8 次，累计本地尝试 15 次；没有网络自动重试，余 1 次未使用。
用户控制台截至第三轮累计 6 次/12337 Tokens/费用小于 0.01 元；按本地尝试保守计数 7 次。
第四轮八次全部 Usage 完整、输入与本地计量逐次一致，47177 Tokens，
高峰缓存未命中保守估算 0.127858 元，实际账单未独立确认。

第三轮四次正常后结构提交 HTTP 400；Checkpoint 重建 SHA-256 和 4517 计量与原意图完全相等。
修正普通 assistant 历史尾部请求回合及空 tool_calls 序列化，新请求计量 4531，仍完整先计量再发送。
正式红测先复现；相关 40、生产 App 补充 6、Python 全量 365（304.69 秒）通过。
第四轮结构提交已被服务端接受，确认请求格式路径接通；未知服务端原 400 正文仍无法追溯。

第四轮验证了自主五工具、真实原 ID 续接、两页 Artifact 正文/Cursor 与 201 行 FULL 覆盖，
随后 EVIDENCE_REFERENCE_INVALID，FAILED + RELEASED，Evidence/Result 0。
实际结构提交正文未保存，具体失败引用无法确认；后续先补有界脱敏诊断再复现，
不得放宽来源门禁。实际摘要/独立验证/Result/Java/前端报告路径尚未真实到达。
真实小样仍未通过完整闭环，不建议提交或进入下一批；凭证未进入验收目录/文档。

### Evidence 专项补修交付（2026-10-11）

对应仓库外 26 文档 B5-R01/R02/R03，详见 `docs/review-harness-batch-5-evidence-blocker-fix.md`。
新增受信来源投影、Diff/Artifact 物理及半行坐标、真实 USER_INPUT 引用/合法示例；
共享 Artifact 内容页 Schema 扩展可选坐标，其他身份、hash、State/Result 门禁不放宽。
明确 SubmissionIssue 共用 JSON/Schema 的两次纠错和模型总预算，反馈具体索引/阶段/reason；
Scope/候选/整批材料预校验后用原 Evidence 表单事务发布，失确认核对全部记录，
存储/完整性/取消/未知资源不进入语义重试。
诊断复用认证 SYSTEM TOOL_OUTPUT，仅存摘要、位置及受控引用，不保存原始提交或秘密正文。
自查另外修正连续 Diff 全页读取的覆盖误报及已有 Artifact 脱敏状态丢失；均先红测再修正。

最终本地 Python 382 通过（310.90 秒），新增正式回归 17；Ruff/format 114 文件通过。
Java 正式 clean test 286，通过，本轮新 XML failure/error/skip 0。
干净 Python 3.12 382 通过（300.43 秒）；锁安装/pip check、显式迁移、启动和认证 Readiness通过。
Java Legacy/前端本轮未重跑，历史记录不混入本轮；相关 Schema/差异检查通过。
本轮真实请求 0、未读取凭证，旧真实失败结论仍有效；先复审，后另授权完整真实闭环。
未暂存、未提交、未推送，不进入下一批。


## 2026-10-12T15:18:07+08:00 批次 5 最终无需付费验收交付

最终工作区新鲜矩阵：Python 全量410/干净3.12 410；Java正式286/Legacy39，XML均failure/error/skip=0且独立保存；前端正式57/Legacy3及两构建通过；Schema/预算/上下文专项16（不与全量重复相加），Ruff/格式117文件、差异检查通过。命令、UTC起止时间、退出码和日志位于 C:/Users/ROG/AppData/Local/Temp/code-weave-b5-final-20261012-144612。

原真实Result规范字节通过Java生产导入/幂等/新Store持久读回、身份/hash/引用拒绝；21项实际探针通过。关闭Session且当前仓库不存在仍能读历史；生产React组件经生产Java控制器访问独立Python固定材料，浏览器展示草稿、范围、限制及源码/Diff/原请求，无审批按钮；内部错误Bearer被拒绝。隔离页面/控制器组合，不冒称完整部署登录或整页导航端到端认证。原Result SHA和历史public偏差保持不变。

两个真实目标已零发送预检：冲突输入初始1450Tokens，压缩目标六文件37848材料Tokens/初始1492Tokens。软水位22118/硬27648不修改；实际触发取决于模型路线，未压缩必须记未验。建议后续各1 Run×16调用，共最多32新请求，新增价格上界2.818048元；原保守上界加总3.396558元，执行前重新核价并确认本轮授权。此轮零付费，不复用旧额度自动重跑。

统一记录 docs/review-harness-batch-5-final-offline-acceptance.md。剩余真实事实冲突/压缩未验；POSIX、远端取消、总体模型质量和主站tokenizer资产核实限制保留。产品实现/正式测试未改，仅验收文档和仓库外工具。无新产品阻塞，不批准整体提交；未暂存/提交/推送，不进入批次6。


## 2026-10-12 15:26–15:27 +08:00 授权后的两个真实目标复验

用户授权各16次、总32次；官方价格重新核对保持输入2/输出8元每百万，累计管理上界3.396558元。实际发送15次，无自动重跑。产物 C:/Users/ROG/AppData/Local/Temp/code-weave-b5-paid-targets-evz1x_f7。

事实冲突：8调用全部Usage有效且输入计量相等；SUCCEEDED/RELEASED，Result COMPLETED/FULL，独立VALIDATION实际发生。但Finding.trigger仍称public，assumptions沿用public描述，validationSummary误称无冲突，未观察有效correctedCandidate，因此质量目标失败，不可因包交付成功判通过。原Result规范字节/摘要只读核对通过，保留新失败产物。

压缩：6调查调用Usage有效且计量相等；第7个实际purpose=COMPACTION，HTTP200/640字节，RESPONSE_SCHEMA choices[0].message value_error，无有效Usage。该校验位置对应无有效正文/工具的assistant；原响应未保存，不能断言具体为空串/null/空白。Run FAILED/RELEASED，无有效Summary续接/Result，真实压缩仅发送已观察，成功目标未验。两Run执行副本均删除。

费用：事实目标峰值估计0.110168元；压缩6条有效Usage峰值估计0.111030元，最后未知保留0.088064元。原0.578510加已知新费用=0.799708，含未知预留保守上界0.887772元，不是实际扣费。费用未知已停止，无追加请求。新语义失败说明仅指令不足保证事实核对；后续离线分析验证协议应明确可审计事实核对结果，不能public替换、放宽门禁或收费盲重试。未修改生产代码；未提交/推送/进入下一批。


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


## 2026-10-12 18:27–18:28 +08:00 补修后真实复验

用户指令“我们进行真实复验”，本轮两独立Run、各最多16次、总最多32次；沿用公开合成冲突和六文件压缩输入，重核官方价格不变，前保守0.887772元+新增预留2.818048元，管理上界3.705820元。实际发送18次，全部Usage有效且输入计量逐次相等，无未知费用停止项，无自动重跑。产物 C:/Users/ROG/AppData/Local/Temp/code-weave-b5-paid-targets-hzm11qby。

事实目标Run 02bb91f1-c6e4-4ca1-8dc4-1f848dd2ce2e：9调用，FAILED/RELEASED，无Result。先纠正缺claimKind消耗一次全局纠错；两次VALIDATION分别在factChecks[2]、[3]被FACT_SOURCE_INVALID阻断（来源不属于候选确认Evidence集合），后已耗尽两次纠错。验证回复同时写VERIFIED和counterexampleHolds=true，这属于模型判断矛盾，但最终先被来源门禁拒绝，未生成有效Finding。真实事实冲突修正目标失败，不把安全拒绝当质量验收成功。

压缩目标Run e360534c-7542-41c4-92a8-23bde9ef240f：9调用（8调查+1COMPACTION），FAILED/RELEASED，无Result。最后HTTP200/1483字节，新增安全诊断finishReason=stop、contentState=NONEMPTY、toolCallsState=ABSENT、usageState=OBJECT，stage=VALIDATED_RESPONSE；输入18022与实际相等。Provider响应有效后，压缩摘要规范化COMPACTION_SUMMARY_INVALID拒绝，未发布有效Summary、未发生压缩后续接。摘要正文未保留，具体Schema字段错误仍未知。不能认定旧空响应根因已修复，也不能放宽校验或盲重试。

费用：事实目标峰值上界0.130262元，压缩0.177678元，共新增0.307940元；含历史未知保守预留的累计上界1.195712元，不是实际扣费。此次不需要因Usage未知再索取账单确认。两Run无执行副本/staging-run残留，Result表均0。验收摘要各自保留。未修改产品实现/测试，未提交/推送/进入下一批。两个目标仍未通过，后续需离线定位引用反馈和摘要规范化诊断，不自动重跑。


## 2026-10-12T18:41:34+08:00 候选来源投影与摘要规范化诊断交付

本轮零付费请求。只读证据：最新事实候选只引用2条Evidence，Run有5条，两次验证引用了额外Run记录；候选级与Run级确认范围混淆已独立定位，门禁拒绝正确。证据存于最新复验根 fact-conflict/offline-reference-scope-diagnosis.json。压缩仅能确认ProviderResponse有效后JSON/Schema规范化失败；compactionCount=0、summaryVersion=0、summaryArtifactRef=null，原摘要具体字段未知，记录 compaction/offline-summary-stage-diagnosis.json。

最小差异：实际验证请求新增候选级candidateFactSources清单，明确只用该候选Evidence、用户要求使用userQuote、counterexampleHolds语义；原来源/身份/纠错/预算门禁不变。摘要规范化新增COMPACTION_JSON/COMPACTION_SCHEMA安全阶段、最多16条白名单字段路径诊断，不存正文或值，不重试、不放宽摘要校验。

红测：生产请求缺候选来源清单；坏摘要JSON/Schema均只有旧VALIDATED_RESPONSE诊断，先失败后补修通过。新增额外Run Evidence展示但不授权，以及引用额外Run Evidence仍拒绝的两条回归。最终受影响矩阵 `pytest tests/integration/test_review_fact_correction.py tests/integration/test_real_review.py tests/integration/test_evidence_protocol.py tests/integration/test_deepseek_adapter.py tests/integration/test_provider_http.py tests/integration/test_real_context.py tests/integration/test_submission_feedback.py tests/integration/test_manual_paid_budget.py -q --tb=short --basetemp=C:/Users/ROG/AppData/Local/Temp/code-weave-b5-sourceprojection-normalize-20261012`：119通过，359.81秒。额外边界单独2通过/15.13秒、初始红转绿3通过/6.06秒均与最终矩阵重叠，不重复加总。共享Schema8通过/0.42秒；Ruff、format117文件和差异检查通过。

本次仅修改runtime/review.py、runtime/deepseek.py和test_review_fact_correction.py、test_deepseek_adapter.py；未修改外部Result、Java/UI、生产压缩策略、取消/资源/预算。旧Java/UI历史证据保留；Python全量/干净410仍为历史，不冒称新代码全量。新增投影改变实际请求Token数，不把旧真实计量对照视为新请求认证。

不能证明模型下一次一定正确引用/识别冲突；没有定位原摘要具体错误，也没有修复旧空响应根因或证明真实压缩成功。待设计窗口独立复审后再决定是否授权有诊断的真实复验。本轮不自动付费重跑，未提交、未推送、不进入批次6。详见 docs/review-harness-batch-5-real-target-offline-diagnosis.md。


## 2026-10-12 19:21–19:22 +08:00 来源投影补修后真实验收

用户指令“我们进行真实验收”，继续两个公开合成目标，各独立Run最多16次，总32次；重新核价输入2/输出8元每百万，前保守上界1.195712加新预留2.818048=管理上界4.013760元。实际发送13次，全部Usage有效、估计输入与实际逐次相等，无未知Usage，无自动重跑。产物 C:/Users/ROG/AppData/Local/Temp/code-weave-b5-paid-targets-x2zgrozv。

事实目标6次，Run 6915aa51-7daa-4d42-a6ec-5973b907d1b7 SUCCEEDED/RELEASED，COMPLETED/FULL，零候选/零Finding/零VALIDATION。模型先识别null回归却提交空evidenceIds，再用从内容hash构造的未确认UUID；两次纠错后移除候选，仅提交Evidence并COMPLETE。这是有效包交付，但未达到支持null Finding、独立事实核对/修正目标，必须记未通过，不能把零Finding冒称代码正确。Result canonical bytes/hash吻合并保留不改。

压缩目标7次，Run f7132f83-d765-4e0a-9519-e0e1332f1df2 FAILED/RELEASED，无Result。新增诊断明确COMPACTION_SCHEMA，sourceRefs[0..6]均model_type：元素没有满足SourceRef对象结构。HTTP200/3813字节、finishReason stop、content NONEMPTY、无tools、Usage OBJECT。原响应正文仍未保存，不能确定元素究竟是字符串还是其他非对象类型；不能推断旧空响应的根因。未发布有效摘要，压缩后续接未完成。后续有明确离线方向：核对摘要SourceRef协议是否充分传达，用真实失败形态构造回归和最小协议提示补修，不放宽Schema，不继续无目标付费。

本轮费用峰值上界：事实0.076682、压缩0.131360，新增合计0.208042元；含旧保守未知预留的累计上界1.403754元，不等于实际账单。所有请求Usage有效，无需再因未知费用要求控制台数字。两Run执行副本/staging-run均不存在。没有产品实现/测试修改，未提交/推送/进入下一批。两个真实目标仍未通过，停止自动请求，后续离线定位并交复审。


## 2026-10-12 批次 5A 摘要协议局部收敛（冻结稿34）

此项显式替代旧摘要模型输出summaryText/referencedArtifactIds/sourceRefs的要求。真实COMPACTION仅纯文本、无tools、无json_object；模型负责叙事，平台负责Summary结构来源。既有“摘要带来源”指持久Summary产物，不要求模型重新生成UUID/hash/来源对象。INVESTIGATION/VALIDATION及Evidence/Candidate/factChecks不变。

平台从进入压缩前已确认State、前序有效Summary与闭合prefix结构引用去重合并，Summary和压缩后State共用受信集合。保留来源归属/hash、FULL发布、完整请求计量、Token严格下降、Checkpoint确认、取消与预算门禁；来源集合超限明确失败，不删锚点求成功。正文中的未知ID或JSON样式文本不注册来源或权限。

正文脱敏后须非空，最多8192字符/16384 UTF-8字节；截断、工具调用、Usage/漂移异常仍拒绝，不自动重试/换模型。FakeAdapter协议独立，不要求真实子项返回模型引用。持久Summary/State及外部Result2.0结构不变，旧产物不改。细节与新鲜离线矩阵见 docs/review-harness-batch-5a-compaction-text-protocol.md；新模板尚无付费验收，旧JSON样例不认证新请求。Evidence/Candidate句柄不在本项冻结范围。


## 2026-10-12T19:56:30+08:00 摘要纯文本子项最终离线交付

完整阅读33复盘、34冻结稿后仅调整摘要。先红：合法文本被旧JSON/sourceRefs协议阻断；prefix-only SourceRef在旧Summary遗漏。绿测后扩大矩阵 `pytest tests/integration/test_compaction.py tests/integration/test_real_context.py tests/integration/test_deepseek_adapter.py tests/integration/test_deepseek_meter.py tests/integration/test_provider_http.py tests/integration/test_model_calls.py tests/integration/test_real_schema_fixtures.py tests/integration/test_real_review.py -q --tb=short --basetemp=C:/Users/ROG/AppData/Local/Temp/code-weave-b5a-text-compaction-20261012`：108通过，183.52秒。末尾新增工具响应拒绝测试后，最终摘要/Adapter/Context/Meter矩阵74通过，36.78秒；之后仅新增脱敏发布测试，单独1通过，1.45秒，产品代码未再改。这些计数重叠，不相加冒称全量。

Ruff通过，format117文件通过，git diff --check通过；4个相关静态Schema与model_json_schema一致且严格结构未变，扩大矩阵含8共享正反例。没有需要重新导出的Schema字段变化，旧CompactionResponse未被共享导出，内部移除不遗漏资产。

纯文本摘要COMPACTION无tools/json_object，原Provider身份/finish/Usage/Token漂移保持；最多8192字符/16384UTF8字节，脱敏后非空；JSON样式文本不解析身份。平台来源由State/前序Summary/闭合prefix结构引用合并，Summary和保留State共用集合；超限失败，归属/hash不放宽。连续三次真实Adapter离线压缩保留Evidence/Candidate，未知ID叙事不注册来源，随后工具原ProviderID配对续接。Fake独立协议保留，不强迫生产模型生成引用。

改动范围：domain/provider.py、runtime/deepseek.py、runtime/context.py、runtime/compaction.py；测试test_compaction.py/test_real_context.py/test_deepseek_adapter.py/test_deepseek_meter.py；ADR0015/0016及交付文档同步。Evidence/Candidate/factChecks/句柄、Result2.0、Java/UI、Run事务不改。旧Summary/Result/诊断保留。

本轮零付费、未读取密钥、未暂存/提交/推送、不进入批次6。旧Python全量/干净410是历史，不认证当前代码；Java/UI外部协议未变保留已有证据。新纯文本请求模板只有离线19Tokens样例，旧JSON托管样例不认证新模板；真实压缩仍待独立复审后另行授权。最多一次探索、明确补修后最多一次确认，同原因再失败回设计窗口，不无限叠提示。模型叙事真实性/语义无损未保证。

详情 docs/review-harness-batch-5a-compaction-text-protocol.md。请求设计窗口独立复审摘要子项，不扩大Evidence句柄范围。


## 2026-10-12 20:05–20:06 +08:00 纯文本摘要一次真实探索

用户授权由开发判断额度并开始；本次仅压缩单目标一次探索Run，最多16请求，按重核官方价新预留1.409024元，叠加前保守1.403754元管理上界2.812778元。公开六文件合成输入，未降低生产水位/门禁/预算，未注入回复。产物 C:/Users/ROG/AppData/Local/Temp/code-weave-b5-paid-targets-nv_19v9k/compaction。

Run b1dd0bed-5c7a-416f-8b48-58c4b69cb46f，实际11请求：9调查+1COMPACTION+1压缩后调查。纯文本COMPACTION成功（估计/实际输入19082一致），Summary1 FULL发布，Checkpoint revision52/53保存summaryVersion1。摘要Artifact内容hash与引用吻合，平台sourceRefs与保留State一致；没有旧JSON/sourceRefs模型输出失败。成功替换证明通过原平台严格Token下降检查；具体替换前完整调查请求Token仍需离线重建，不以不同purpose请求直接作下降比较。

压缩后调查请求本地7897、实际7975，少算78，触发PROVIDER_TOKEN_METER_DRIFT。Provider返回tool_calls，但由于漂移门禁未执行这些工具，未完成续接或Result。Run FAILED/RELEASED，无执行副本/staging-run残留，Result不可用。不能认定整个摘要验收通过；已观察摘要/Checkpoint替换，剩余Token计量、工具续接和诚实交付阻塞。

11次全部有效Usage，无未知费用；本轮峰值价格费用上界0.236118元，保守累计1.639872元，不是实际扣费。失败后停止，不自动重跑；后续先离线重建最后请求和计量边界，不能固定加78/放宽冻结门禁碰运气。本轮无产品改动，未提交/推送/进入批次6。保留旧失败和新Summary产物，最多一次确认Run须明确修复及后续许可，不以本条自动发送。


## 2026-10-12 纯文本压缩后78Token差异：离线定位

已从持久Checkpoint revision53、完整Summary Artifact、原Manifest和ModelCall精确重建最后调查请求：SHA sha256:14118200bb7c3c3e1d7876b6176af80c246df97e36cf576c910bba03706fb065与发送记录完全一致，本地7897再次复现，实际Usage7975、差78。请求21502 UTF8字节，9消息，角色system/user/user/user/assistant/4tool，严格ProviderRequest及工具配对通过，无json_object，tools auto。逐正文Token扫描未发现参考Tokenizer特殊控制Token。

对同一实际调查模板重建压缩前最后有效State请求22445，压缩后7897，减少14548，确认原平台Token下降门禁有实际依据；这比不同purpose计量比较更可靠。Summary来源19项、Checkpoint替换及前序有效记录保留。产物 compaction/offline-request-reconstruction.json 与根reconstruct_meter_offline.py（只读重建、零发送）提供复现。

结论：排除了“发送与持久摘要不一致”和本地不可重复计量；没有证据证明78来自具体字段、Provider模板差异或Token资产问题。前10次实际Usage均相等，只有压缩后视图出现漂移，但相关性不能证明摘要角色或多工具是根因。纯离线无法观察Provider私有编码过程，仍未知。没有改编码器、添加固定78、放宽门禁、清除漂移或付费重跑。

下一步需设计窗口评估有限诊断对照（原字节与单变量消息投影）或显式预算治理政策；未经确认不把任意投影当生产修复。摘要成功/Checkpoint替换/Token下降已有真实证据，工具执行续接与Result仍未通过。当前未改产品代码，不需重跑已通过矩阵；未提交/推送/进入批次6。
