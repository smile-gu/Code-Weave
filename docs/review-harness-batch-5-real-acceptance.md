# 批次 5 真实合成验收记录

日期：2026-10-11，Asia/Shanghai。状态：**未通过完整审查闭环，不建议提交或进入下一批。**

范围：官方 DeepSeek `deepseek-flash`、固定公开合成 Java/说明材料、Python 生产 App/Adapter。
没有上传项目源码，没有注入 Provider transport 或 Executor；独立临时 DB/Checkpoint/材料目录。
用户提供临时凭证并授权 Agent 本机隐藏输入；本文及验收文件不保存凭证。

## 授权与费用

初始最多 8 次、人民币 1 元管理预算。用户随后明确提高累计发送上限到 16 次，预算仍 1 元。
本地累计 15 个发送意图/尝试：1 + 1 + 5 + 8；按尝试保守计数，不按控制台成功请求数减免。
尚余 1 次；没有自动重试或继续发送。网络取消不保证远端零计费。

截至第三轮后用户反馈控制台：6 次、12337 Tokens、累计费用小于 0.01 元；
Tokens 与前两轮 2696 + 第三轮四个有效响应 9641 一致。本地为 7 次发送尝试，
第七次 HTTP 400 无 Usage；控制台数字与本地事实分别保存，不改写调用记录。
第四轮 Usage 完整，47177 Tokens；按官方高峰缓存未命中价格保守估算 0.127858 元。
加已确认此前费用，累计保守估计小于 0.137858 元；第四轮实际账单未独立确认。
每轮调用前重新核对官方中文价格页并预留本轮最大用量，未超过管理预算。

## 四轮结果

| 轮次 | 临时目录尾部 | 本地调用数 | 结果 |
|---|---|---:|---|
| 1 | code-weave-real-smoke-63f70ba7 | 1 | PROVIDER_RESPONSE_INVALID；字段诊断未保存，具体原因无法追溯 |
| 2 | code-weave-real-smoke-2896lwf4 | 1 | HTTP 200；工具 index、prompt_tokens_details 被严格模型拒绝 |
| 3 | code-weave-real-smoke-zc4vn8um | 5 | 四个有效响应，随后结构提交 HTTP 400；FAILED + RELEASED |
| 4 | code-weave-real-smoke-jojc66fs | 8 | 全部有效响应，随后 EVIDENCE_REFERENCE_INVALID；FAILED + RELEASED，无 Result |

目录均位于 `C:/Users/ROG/AppData/Local/Temp/`，保留原始调用事实、Checkpoint、Artifact 和安全摘要。
第三、第四轮另有 acceptance-summary.json；第三轮另有 request-reconstruction-summary.json。

## 已修正的接收与请求边界

观察到的工具 index 和缓存明细在 Adapter 接收边界严格校验后转换为平台规范格式；
索引不替代原始 Provider ID，缓存明细须与总量及已有 hit/miss 一致，未知字段仍拒绝。

第三轮的 400 请求已从 Checkpoint 在内存重建，SHA-256 与原意图完全一致，
本地计量复现 4517 Tokens。请求以普通 assistant 历史结尾，且包含空 tool_calls。
修正后保留全部历史，增加新的请求回合并省略空 tool_calls；固定请求为 4531 Tokens，
完整增量仍计量并保存 Manifest。没有启用 beta prefix 或删除历史来绕过预算。
原 400 错误正文未捕获，不能证明它是唯一服务端原因；第四轮结构提交请求已被服务端接受。

正式红测先复现，修正后相关回归 40 通过，生产 App 补充 6 通过；
Python 全量 365 通过，304.69 秒；Ruff/format 通过。
Java/前端/干净环境本轮未重跑，先前证据与本轮证据分开计数。

## 真实取得的证据

- 第三轮四个有效响应的实际输入依次为 1283、1903、2491、3061，均等于本地计量。
- 第四轮八个实际输入为 1284、1908、2564、4781、6645、8119、8137、8155，偏差全部为零。
- 第四轮模型自主执行 git_diff、read_file、search_code、read_artifact、glob_files；
  共 8 个工具调用及 8 个原 Provider ID 映射，进入下一轮时无待配对调用。
- 大输出经 Artifact 分页读取，同一 Artifact 两页正文分别为 7313/5254 字节，
  首页面有 Cursor，尾页面结束；State 记录 src/Demo.java 第 1–201 行 FULL 覆盖。
- 结构输出发生两次有界纠错，全部计入八次模型总额；未获得独立 VALIDATION 调用。
- 最终 Evidence 引用校验拒绝，review_evidence/review_results 均为 0；
  Run 没有假写 SUCCEEDED，资源为 RELEASED，执行副本已收尾，诊断 Artifact 保留。
- 对 Agent 执行的两轮验收目录扫描，未发现凭证形态字节。

## 剩余阻塞

第四轮拒绝了 Evidence 引用，但结构化提交正文未留存，故无法从现有证据判断
具体是哪个来源摘要、行号或用户输入引用有误。不能据此断言平台来源校验有缺陷，
也不能宣称模型问题已修复。后续须补充有界、脱敏的结构提交/引用失败诊断，再离线复现；
不得关闭哈希、坐标、身份或来源门禁换取成功。

实际压缩与摘要续接未观察到；Evidence 成功持久化、独立候选验证、原子 Result、
Java 真实结果导入与前端实际报告未在真实小样中到达。POSIX、远端取消和模型审查质量未验证。
当前不能宣称批次 5 真实验收完成。项目未暂存、未提交、未推送。

## 第五轮：B5-R04 关闭后的真实复验（2026-10-11 22:19–22:20 +08:00）

用户确认此前控制台累计费用 0.05 元、14 次、59514 Tokens；本地历史仍按 15 次发送尝试计数，不以控制台成功数抵扣。已获授权下一轮最多 8 次、累计上限 24 次、累计预算 1 元，由 Agent 隐藏输入既有临时凭证执行。官方价格重新核验通过，本轮最坏预留 0.704512 元，加已确认此前费用为 0.754512 元。

本轮目录 `C:/Users/ROG/AppData/Local/Temp/code-weave-real-smoke-i4f52b9y`，Run `b1a0234c-1265-412e-9635-fa2b4e109a0b`。生产 App/Adapter，公开合成输入，没有上传项目源码、没有注入模型回复或工具执行结果。

结果：6 次请求后 FAILED + RELEASED，失败码 PROVIDER_STRUCTURED_OUTPUT_INVALID。6 条 Usage 完整，无远端结果未知；输入合计 22644、输出 5127 Tokens，合计 27771。全部输入 Token 计量等于 Provider 实际值。按高峰缓存未命中价格估算本轮 0.086304 元；加此前已确认 0.05 元为 0.136304 元，本轮实际账单仍须控制台独立确认。

三条完整性核验通过的受控诊断均为 SCHEMA / SCHEMA_INVALID：

1. candidates[0].claimKind。
2. evidenceCandidates[0].sourceRefs[0].CODE.<unknown-field>（未知字段按保密约束隐藏）。
3. candidates[0].evidenceIds。

两次有界纠错后仍不满足 Schema，未进入 Evidence 引用语义校验或独立 VALIDATION。review_evidence、review_results 均为 0，没有假写成功，执行副本已移除，诊断 Artifact 保留。既有来源/Diff修复不能由此判为失败；诊断仅证明字段级 Schema 错误，不足确定缺字段、错误取值或更具体原因，也不据此认定完全属于模型责任。

本轮凭证形态扫描 0 文件命中；未保存密钥到报告。安全摘要 acceptance-summary.json 已写入该临时目录。没有自动付费重跑、没有修改生产实现、未暂存、未提交、未推送。

累计本地尝试 21/24，剩余 3 次，不视为足够一次完整闭环的额度。真实 Evidence → 独立验证 → Result 仍未验收通过；下一步离线核对结构提交约束与具体纠错反馈，再另行安排真实闭环，不放宽 Schema/引用门禁。Java、前端和其他测试矩阵本轮未重跑。
## 第六轮：纠错补修复审后的真实复验（2026-10-12 09:04–09:05 +08:00）

用户确认此前控制台累计 0.09 元、20 次、87285 Tokens，并授权本轮最多 8 次、累计上限 32 次、总预算仍 1 元。此前本地按 21 次发送尝试计数。官方价格重新核验通过，本轮最大预留 0.704512 元，加此前确认费用为 0.794512 元。

目录 `C:/Users/ROG/AppData/Local/Temp/code-weave-real-smoke-i7htbmzk`，Run `6a30d890-58ab-4d10-b842-f88c1c0df32e`。官方 DeepSeek，Python 生产 App/Adapter；原固定公开合成输入，保留约 200 行填充以覆盖 Artifact 大输出与分页。未注入模型回复、未上传项目源码。

**取得进展，但完整审查闭环未通过。** Run SUCCEEDED + RELEASED；Result 2.0 已交付，Pass 为 INCOMPLETE，原因 CONTEXT_MODEL_CALL_LIMIT_EXCEEDED / SOURCE_COVERAGE_PARTIAL。3 条 Evidence 已可靠保存并与 Result 副本逐项一致；Result API 字节与持久 document_bytes 一致，摘要同时吻合 Run 引用及持久结果。Finding 0，独立 VALIDATION 0，不能据此宣称代码没有问题或审查通过。

本轮发送 8 次，Usage 全部完整，无远端结果未知；输入合计 39691、输出 3677，共 43368 Tokens，全部本地输入计量等于 Provider 返回值。高峰缓存未命中估算 0.108798 元，加此前已确认账单 0.09 元为 0.198798 元；本轮实际费用仍待控制台独立确认。本地累计尝试 29/32，余 3 次，无自动重跑。

受控诊断明确首次结构提交 candidates[0].claimKind 是 missing，而非只能定位字段。一次有界纠错后，模型已提交合法证据、平台确认了 3 个 Evidence ID。但最终候选池仍为 0，后续结构提交在调用预算门禁被阻断，没有请求 VALIDATION。源码只读到了转存 Artifact 的部分页面，未确认连续读完，故保留 PARTIAL。

Checkpoint 以最大 revision=31 确认最终状态，不按随机 checkpoint_id 字典序推断最新。最终 correctionCount=1、modelCallCount=8、evidenceRefs=3、candidates=0、compactionCount=0、pendingToolCalls=[]。执行目录和该 Run staging 均已移除；仅保留空 .staging 管理目录，不是残留执行副本。凭证形态扫描 0 文件命中。

本轮证明 Schema 具体反馈、证据可靠保存、预算耗尽时诚实 INCOMPLETE Result 及资源释放路径已真实到达；未证明独立候选验证、完整源码覆盖、真实压缩或 Java/UI 实际导入。未重跑离线全量/Java/前端/干净环境，未修改生产实现、未暂存/提交/推送。证据摘要保存为 acceptance-summary.json。

下一轮建议明确拆分验收目标：使用不含大段填充的最小合成输入专测 Evidence → 独立验证 → Result；保留本轮大输出/分页的真实部分覆盖证据，不把删去填充后的成功冒称大输出全路径通过。剩余 3 次不足安排完整闭环，新增请求与费用须另行确认；不放宽引用、覆盖或预算门禁，不批准整批提交或下一批。

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


## 2026-10-12 15:26–15:27 +08:00 授权后的两个真实目标复验

用户授权各16次、总32次；官方价格重新核对保持输入2/输出8元每百万，累计管理上界3.396558元。实际发送15次，无自动重跑。产物 C:/Users/ROG/AppData/Local/Temp/code-weave-b5-paid-targets-evz1x_f7。

事实冲突：8调用全部Usage有效且输入计量相等；SUCCEEDED/RELEASED，Result COMPLETED/FULL，独立VALIDATION实际发生。但Finding.trigger仍称public，assumptions沿用public描述，validationSummary误称无冲突，未观察有效correctedCandidate，因此质量目标失败，不可因包交付成功判通过。原Result规范字节/摘要只读核对通过，保留新失败产物。

压缩：6调查调用Usage有效且计量相等；第7个实际purpose=COMPACTION，HTTP200/640字节，RESPONSE_SCHEMA choices[0].message value_error，无有效Usage。该校验位置对应无有效正文/工具的assistant；原响应未保存，不能断言具体为空串/null/空白。Run FAILED/RELEASED，无有效Summary续接/Result，真实压缩仅发送已观察，成功目标未验。两Run执行副本均删除。

费用：事实目标峰值估计0.110168元；压缩6条有效Usage峰值估计0.111030元，最后未知保留0.088064元。原0.578510加已知新费用=0.799708，含未知预留保守上界0.887772元，不是实际扣费。费用未知已停止，无追加请求。新语义失败说明仅指令不足保证事实核对；后续离线分析验证协议应明确可审计事实核对结果，不能public替换、放宽门禁或收费盲重试。未修改生产代码；未提交/推送/进入下一批。


## 2026-10-12 最新真实调用费用确认

用户确认 DeepSeek 控制台累计 ¥0.43、47 次请求、264424 Tokens，覆盖最近两目标验收。与本地今日此前24+最小闭环8+本轮15=47次一致。账单确认解除费用未知停止条件，但不能填补最后COMPACTION缺失的逐调用Usage，也不能改变其失败/未知远端记录。保守累计预留上界0.887772元仍保留，不当作实际扣费；两个目标失败结论不变，不自动重跑。本条零请求、无实现修改。确认材料保存在验收根user-billing-confirmation.json。


## 2026-10-12 18:27–18:28 +08:00 补修后真实复验

用户指令“我们进行真实复验”，本轮两独立Run、各最多16次、总最多32次；沿用公开合成冲突和六文件压缩输入，重核官方价格不变，前保守0.887772元+新增预留2.818048元，管理上界3.705820元。实际发送18次，全部Usage有效且输入计量逐次相等，无未知费用停止项，无自动重跑。产物 C:/Users/ROG/AppData/Local/Temp/code-weave-b5-paid-targets-hzm11qby。

事实目标Run 02bb91f1-c6e4-4ca1-8dc4-1f848dd2ce2e：9调用，FAILED/RELEASED，无Result。先纠正缺claimKind消耗一次全局纠错；两次VALIDATION分别在factChecks[2]、[3]被FACT_SOURCE_INVALID阻断（来源不属于候选确认Evidence集合），后已耗尽两次纠错。验证回复同时写VERIFIED和counterexampleHolds=true，这属于模型判断矛盾，但最终先被来源门禁拒绝，未生成有效Finding。真实事实冲突修正目标失败，不把安全拒绝当质量验收成功。

压缩目标Run e360534c-7542-41c4-92a8-23bde9ef240f：9调用（8调查+1COMPACTION），FAILED/RELEASED，无Result。最后HTTP200/1483字节，新增安全诊断finishReason=stop、contentState=NONEMPTY、toolCallsState=ABSENT、usageState=OBJECT，stage=VALIDATED_RESPONSE；输入18022与实际相等。Provider响应有效后，压缩摘要规范化COMPACTION_SUMMARY_INVALID拒绝，未发布有效Summary、未发生压缩后续接。摘要正文未保留，具体Schema字段错误仍未知。不能认定旧空响应根因已修复，也不能放宽校验或盲重试。

费用：事实目标峰值上界0.130262元，压缩0.177678元，共新增0.307940元；含历史未知保守预留的累计上界1.195712元，不是实际扣费。此次不需要因Usage未知再索取账单确认。两Run无执行副本/staging-run残留，Result表均0。验收摘要各自保留。未修改产品实现/测试，未提交/推送/进入下一批。两个目标仍未通过，后续需离线定位引用反馈和摘要规范化诊断，不自动重跑。


## 2026-10-12 19:21–19:22 +08:00 来源投影补修后真实验收

用户指令“我们进行真实验收”，继续两个公开合成目标，各独立Run最多16次，总32次；重新核价输入2/输出8元每百万，前保守上界1.195712加新预留2.818048=管理上界4.013760元。实际发送13次，全部Usage有效、估计输入与实际逐次相等，无未知Usage，无自动重跑。产物 C:/Users/ROG/AppData/Local/Temp/code-weave-b5-paid-targets-x2zgrozv。

事实目标6次，Run 6915aa51-7daa-4d42-a6ec-5973b907d1b7 SUCCEEDED/RELEASED，COMPLETED/FULL，零候选/零Finding/零VALIDATION。模型先识别null回归却提交空evidenceIds，再用从内容hash构造的未确认UUID；两次纠错后移除候选，仅提交Evidence并COMPLETE。这是有效包交付，但未达到支持null Finding、独立事实核对/修正目标，必须记未通过，不能把零Finding冒称代码正确。Result canonical bytes/hash吻合并保留不改。

压缩目标7次，Run f7132f83-d765-4e0a-9519-e0e1332f1df2 FAILED/RELEASED，无Result。新增诊断明确COMPACTION_SCHEMA，sourceRefs[0..6]均model_type：元素没有满足SourceRef对象结构。HTTP200/3813字节、finishReason stop、content NONEMPTY、无tools、Usage OBJECT。原响应正文仍未保存，不能确定元素究竟是字符串还是其他非对象类型；不能推断旧空响应的根因。未发布有效摘要，压缩后续接未完成。后续有明确离线方向：核对摘要SourceRef协议是否充分传达，用真实失败形态构造回归和最小协议提示补修，不放宽Schema，不继续无目标付费。

本轮费用峰值上界：事实0.076682、压缩0.131360，新增合计0.208042元；含旧保守未知预留的累计上界1.403754元，不等于实际账单。所有请求Usage有效，无需再因未知费用要求控制台数字。两Run执行副本/staging-run均不存在。没有产品实现/测试修改，未提交/推送/进入下一批。两个真实目标仍未通过，停止自动请求，后续离线定位并交复审。


## 2026-10-12 20:05–20:06 +08:00 纯文本摘要一次真实探索

用户授权由开发判断额度并开始；本次仅压缩单目标一次探索Run，最多16请求，按重核官方价新预留1.409024元，叠加前保守1.403754元管理上界2.812778元。公开六文件合成输入，未降低生产水位/门禁/预算，未注入回复。产物 C:/Users/ROG/AppData/Local/Temp/code-weave-b5-paid-targets-nv_19v9k/compaction。

Run b1dd0bed-5c7a-416f-8b48-58c4b69cb46f，实际11请求：9调查+1COMPACTION+1压缩后调查。纯文本COMPACTION成功（估计/实际输入19082一致），Summary1 FULL发布，Checkpoint revision52/53保存summaryVersion1。摘要Artifact内容hash与引用吻合，平台sourceRefs与保留State一致；没有旧JSON/sourceRefs模型输出失败。成功替换证明通过原平台严格Token下降检查；具体替换前完整调查请求Token仍需离线重建，不以不同purpose请求直接作下降比较。

压缩后调查请求本地7897、实际7975，少算78，触发PROVIDER_TOKEN_METER_DRIFT。Provider返回tool_calls，但由于漂移门禁未执行这些工具，未完成续接或Result。Run FAILED/RELEASED，无执行副本/staging-run残留，Result不可用。不能认定整个摘要验收通过；已观察摘要/Checkpoint替换，剩余Token计量、工具续接和诚实交付阻塞。

11次全部有效Usage，无未知费用；本轮峰值价格费用上界0.236118元，保守累计1.639872元，不是实际扣费。失败后停止，不自动重跑；后续先离线重建最后请求和计量边界，不能固定加78/放宽冻结门禁碰运气。本轮无产品改动，未提交/推送/进入批次6。保留旧失败和新Summary产物，最多一次确认Run须明确修复及后续许可，不以本条自动发送。
