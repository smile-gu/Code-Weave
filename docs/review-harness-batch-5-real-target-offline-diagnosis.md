# 批次 5 两个真实失败目标：离线定位与最小补修

## 第二次真实复验后的离线跟进（2026-10-12）

复验根 `C:/Users/ROG/AppData/Local/Temp/code-weave-b5-paid-targets-hzm11qby`。本次跟进无付费请求，不修改历史 Run 或 Result。

**候选来源范围已确认**：持久候选有 2 个 evidenceIds，Run 一共 5 条 Evidence。两次验证均引用 Run 中存在但不属于候选的记录，第一次索引 2 是 USER_INPUT，索引 3/4 是后来新增的 CODE；纠错后索引 2 改对，索引 3/4 仍不属于候选。`offline-reference-scope-diagnosis.json` 逐项记录归属判断，来源门禁正确拒绝，不能自动把这些 ID 加入候选。

平台原验证指令只称 acknowledged evidenceId，原请求同时展示全部 evidenceRecords，未明确“Run 已确认”和“此候选已确认”的区别。这是可修复的协议表达缺口，但不能证明它是模型失败的唯一原因。最小改动：明确只引用本候选 evidenceIds，验证材料增加候选级 `candidateFactSources`（evidenceId/sourceIndex）清单，仅列原候选已确认的 CODE/Artifact 来源；保留所有原材料供反例核对，不修改候选或 Evidence。用户业务契约放 userQuote，不能拿 USER_INPUT 当实现事实。另明确 counterexampleHolds 是实际成立的反例，而不是“已经考虑过反例”。不放宽引用、全局纠错或预算门禁。

**摘要失败仍存在信息缺口**：最后响应 finishReason=stop、正文 NONEMPTY、Usage 有效；`normalize_response` 的 CompactionResponse JSON/Schema 解析失败，旧诊断停留 VALIDATED_RESPONSE，丢失了实际规范化阶段。原摘要正文未保存，因此无法恢复具体字段。不能宣称是 SourceRef 格式或某个字段错误。

最小诊断补修：解析前分别设置 COMPACTION_JSON 与 COMPACTION_SCHEMA；Schema 失败保留最多 16 条、路径最多 8 段的白名单字段及错误类型，未知字段名替换为占位符，不保留 input、context、异常消息或摘要正文。JSON 失败只标阶段。依旧立即 COMPACTION_SUMMARY_INVALID，无重试、不接受坏摘要、不修改已有有效 State。新增两个红测分别复现坏 JSON 与 Evidence 风格引用误用 SourceRef 的拒绝诊断缺失；后者仅是合成边界样例，不当作真实摘要失败根因。

本次改动范围是 runtime/review.py、runtime/deepseek.py 及两个已有回归文件；外部 Result、Java/UI、生产压缩策略、取消/资源保护不改。候选级清单进入完整实际请求计量，可能改变 Token 数；此前真实计量样例不认证新请求。准确回归结果在末尾追加，旧全量不冒称当前全量。

日期：2026-10-12。本轮零付费请求。先从已保存事实重建，后离线红测和补修；不重放 Run，不改历史 Result，不提交/推送/进入批次 6。

## 实际请求定位依据

原产物根：`C:/Users/ROG/AppData/Local/Temp/code-weave-b5-paid-targets-evz1x_f7`。仓库外 `reconstruct_offline.py` 和 `historical-validation-instruction.txt` 保留复现入口，分别输出 `fact-conflict/offline-request-reconstruction.json` 与 `compaction/offline-request-reconstruction.json`。未保存完整重建请求或密钥。

重建从 Checkpoint、持久 ModelCall、受管 Evidence 和认证材料副本装配；原 Manifest SHA、用户摘要、scope 摘要、材料归属和引用逐项核验。终态执行副本已删除，所以仅在内存重建中替代生命周期检查，不发送、不保存 Checkpoint、不改变 Run。判断“实际送达”以重建请求 canonical SHA 与发送意图摘要**完全相等**为依据，不仅凭文本相似。

| 请求 | Checkpoint revision | SHA256（不含前缀） | 计量 |
| --- | --- | --- | --- |
| VALIDATION f6e72dde-2920-4cfa-9a94-b6dda84d8979 | 28 | f4c1fe12b20db6da69f221ab4a8a5160c467ec7f50f8eb68cbc8982acce9a0ce | 重建 5728 = 意图 5728 = 实际 Usage 5728 |
| COMPACTION b3bbdc39-c3ec-4549-9111-b6450e7b5876 | 33 | 9f52dece80d901f18c339777ad745cb4fdec802ba8ee12d6e9f8f9544a8ff324 | 重建 20472 = 意图 20472；实际 Usage 缺失 |

### 事实冲突

实际验证请求是 4 条消息（system、user、user、user），JSON 模式、无 tools。明确的业务要求/实现事实区分指令、用户 public 描述、默认访问权限源码、完整候选及原始材料全部送达。原 Result 中触发条件仍称 public，验证 basis 声称“no conflicting user description present”。因此本例不能归因于平台漏传规则或原文：模型在完整信息下仍继承错误前提。

定位结论：原 `assumptionsResolved=true` 与自由文本说明无法提供逐事实核对记录；已有 correctedCandidate 通道可传播修正，但模型没有有效使用。没有针对 public 做自动替换，也不引入 Java 解析器。

### 压缩

实际请求为 18 条消息，6 个已闭合的两工具批次，最后一条是 tool；JSON 模式、无新 tools Schema。严格 ProviderRequest 校验与配对通过，输入 20472 未超过 27648。选择的闭合前缀合法，不能仅因最后是 tool 就宣称构造错误或必须加固定回合。没有修改压缩请求构造或降低触发水位。

原响应仅有安全诊断：HTTP 200、640 字节、RESPONSE_SCHEMA、`choices[0].message` 的 `value_error`；对应 AssistantMessage 无有效非空正文且无工具调用的拒绝路径。但原始响应没有保留，**无法区分 null、空字符串或纯空白，无法确认原 finish_reason、是否有额外截断状态、Usage 字段是否存在/有效**。本地调用记录缺失 Usage 不等于服务端一定没有 Usage。不能补造历史形态或计费分项，更不能确定根因是 Provider、提示或回合结构。

补修只提高未来失败诊断可辨识性，不宣称已修复这次压缩空响应，不能用离线合法响应代替真实压缩通过。

## 最小修复差异

1. 内部 CandidateValidation 增加最多 16 条 `factChecks`，每项严格字段：候选原句、已确认 evidenceId、零基 sourceIndex、原文 sourceQuote、可选用户 userQuote、处理枚举和有界说明。复用已有 Text/UUID 上限，未改变外部 Result 2.0。
2. VERIFIED 必须具有核对投影；候选摘录必须来自原候选，来源必须属于候选已确认 Evidence、位置合法、摘录必须来自重新读取的 CODE/Artifact 材料。用户摘录与原请求匹配，用户输入不能当实现事实来源。
3. CORE_CONTRADICTED 必须 REJECTED；UNRESOLVED 不允许 VERIFIED 或 assumptionsResolved=true；CORRECTED 必须有 correctedCandidate，原错误摘录仍留在修正候选时拒绝。修正仍冻结身份、passId、claimKind、Evidence 与代码引用，并重新通过原 Finding 门禁。非法核对进入既有全局两次纠错、16 次调用预算。
4. 验证指令要求比较关键事实、必要假设和影响范围，而不是自报已检查。成功验证回复复用既有脱敏 assistant/Artifact/Checkpoint 存储，留下受控核对记录，不增加第二套事实表或 Evidence 生产者。
5. Adapter 在 strict JSON 后、完整响应 Schema 校验前记录 `responseShape`：finish_reason 仅 stop/length/tool_calls/content_filter 白名单，否则 UNKNOWN；正文仅 ABSENT/NULL/INVALID/EMPTY/WHITESPACE/NONEMPTY；工具与 Usage 仅结构分类。无正文、未知值、异常文本或完整 Usage 副本进入诊断。完整响应校验、漂移保护、取消及无网络重试保持原状。

## 离线证伪与影响

响应诊断红测：null、空串、空白/缺 Usage、length 截断均被原 Schema 拒绝，但没有结构形态可定位，4 条先红；补修后可区分且仍一次调用、仍拒绝。补充未知 finish/非法 Usage 秘密值不能进入诊断。

事实投影红测：新 factChecks 先被旧严格 Schema 拒绝；旧输出无投影却 VERIFIED 原本能成功，缺核对测试明确红。保留冲突用户输入、非核心默认权限修正和 null 回归；原文 guard 或跨包调用核心前提反例拒绝；另测虚构摘录/用户描述/来源、缺修正、未改变错误断言、核心冲突却 VERIFIED 及原身份/Schema/引用/定量门禁。

只运行受影响真实 Runtime、Evidence 协议、Adapter/真实 HTTP、上下文、纠错、费用保护专项；不无差别重跑 Java/UI 或干净环境。准确最终计数在完成后追加，测试执行中变更的中间结果不冒称最终代码通过。

此前 Java/UI 真实历史导入与外部 Schema/只读展示证据保留：本修复没有改变 Result 格式和 Java/UI 代码。此前 410 项 Python 全量和干净环境仍是旧代码的历史证据，**不代表当前补修后的全量通过**；现付费请求格式、Schema大小和计量会变化，旧真实行为/计量有限对照也不能冒称新协议真实认证。

剩余限制：合法摘录只证明来源，模型仍可能遗漏关键事实或错误标注 SUPPORTED；平台不做形式化语义证明。有界投影提高可审计性和结构门禁，不保证真实模型稳定识别所有冲突。原压缩根因仍未知，新诊断必须在未来授权的真实响应中才能补证。成功摘要、压缩后工具续接及最终交付仍未真实验收。本轮不发送请求验证这些未知项。


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
