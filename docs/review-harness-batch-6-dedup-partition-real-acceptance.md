# 批次6：兼容性预分区单次真实复验

记录时间：2026-10-16T22:15:45.331511+08:00。真实执行：2026-10-16 22:01:42–22:06:47 +08:00。HEAD `6ef9a018a06f50b0acb1ae437cf3a8833edc41ec`，叠加已独立复审、尚未提交的兼容性预分区补修；不是纯提交6ef9a01的验收。运行前已保存完整未提交差异及产品源码/摘要。

## 分层结论

| 目标 | 本轮事实与结论 |
|---|---|
| 六Pass、Result、Usage、账本和资源 | 通过：六Pass全部COMPLETED，SUCCEEDED/RELEASED，Result3.0，38次均结算，无未知、预留或超支 |
| 兼容性预分区／完整交付 | 通过：12条原始Finding分成5项ENVIRONMENT_DEPENDENT/RISK、7项STATIC_CORRECTNESS/VERIFIED；每次请求完整交付本组，无混合组 |
| 合法肯定与真实合并成功路径 | **已观察并回放通过，仅限5项RISK组**：合法整组肯定→完整SYSTEM审计→Checkpoint精确确认→最终一条RISK，五份贡献及来源并集保留 |
| VERIFIED组完整判断 | 未完成：两份回复含重复JSON键，且只返回七项中的六项；共享两次纠错耗尽后保留全部七项，未强并 |
| 合法否定 | 本轮未收到；不把否定当失败，也不把非法回复视为合法否定 |
| null回归／良性重构 | null回归检出；requiredLength等价重构c2被REJECTED，未作为缺陷交付 |
| 报告语义质量 | **不能判为全通过**：c11架构Finding存在关键事实表述偏差，仍为VERIFIED，待独立复核；其余重复项因未完成第二组判断仍在 |

本轮不要求5＋4或最终两条。实际是5＋7，最终8条：7条原始VERIFIED及1条聚合RISK。没有为了减少条数增加调用、挑子集合并、放宽JSON/身份/来源门禁或修改原Result。不能从Run成功推断整批质量通过，也不能声称VERIFIED组合并成功路径已验。

## 授权、输入与实现固定

- 仅一个新Run：`8640c4ef-6925-4e7f-adcd-04ad5c5434e4`；独立数据库/Checkpoint/Artifact/执行目录，数据库核对Run数为1。
- Snapshot：`3ae808d8-a0bd-4794-b220-1f142d18d2f0`；Manifest `sha256:0eb71f4e59d7ab1cc35dec7f3f4e1167ed09b65cbc3bc040d5e09b6fe2c753ed`。沿用原固定3个文件，复制后及运行后均SHA一致，未加oracle/缺陷提示。
- 用户输入逐字沿用：

> 请审查当前累计变更，依据仓库中的接口契约与原始源码判断行为变化；区分已证实问题、风险和建议，并如实说明检查范围。

- 79个产品文件与离线独立复审基线一致；保存`head.txt`、`workspace.diff`、`git-status.txt`、`production-digests.json`和`production-source/`。运行前后产品摘要与未提交差异完全相同，本窗口未写产品实现；位置协议未调整。
- 一个Run，管理预算¥3，所有purpose共用60次发送前SQLite FULL持久计数/确认硬门禁，未知发送不退次数，HTTP retries=0。离线70并发预检60放行/10阻断；记录器脱敏和隐藏推理不落盘预检通过；未创建额外模拟Run。
- 密钥按既有明确授权沿用，只经getpass隐藏输入供进程使用，不写配置/脚本/产物，不输出认证头，不保存隐藏reasoning_content。
- 内部健康、价格、能力、认证均READY后仅一次POST创建Run。使用Python正式ASGI入口和既存Java Snapshot；本轮未覆盖Java在线启动、UI、Handoff或真实CC。

## 价格与费用闭环

官方价格原件：https://api-docs.deepseek.com/zh-cn/quick_start/pricing/ 。HTML SHA `69911c5295d9955f36d0188f2aceae4b6c996c897bd19ea2231fd1e5a0952b30`。Profile `official-flash-partition-b91d79328fb1`，核验14:00:56Z、到期20:00:56Z，整个Run位于有效期内。官方模型名deepseek-flash，页面对应DeepSeek-V4.1-Flash。

最高时段/输入不采用缓存优惠的保守价格：输入¥2/百万、输出¥8/百万。本次**¥0.850254为管理保守计费，不是DeepSeek控制台实际账单**；本轮未取得实际账单，不混入此前累计消费。

| 项目 | 数值 |
|---|---|
| 真实发送／可见回复 | 38／38，小于60 |
| purpose | INVESTIGATION 26、VALIDATION 8、DEDUP 4；纠错计入各purpose |
| Usage | prompt295619、completion32377、total327996 |
| 调用 | 38份LOCAL_CLOSED，Usage全有，remoteOutcomeUnknown=0 |
| 预留 | 38份SETTLED，reservedMicros=0，overrunMicros=0；spentMicros=850254 |
| 资源 | Run RELEASED，execution_workspaces=REMOVED，执行副本不存在，.staging为空 |
| Result | ID `c827d437-3ef5-45c4-a0e1-a206700c90df`，3.0，SHA `312727039ce9b3133502aff7e64503dbe9cc3576bd1bf0dfa84329ae8684876b` |
| 完整性 | 52份Artifact SHA/字节数匹配；172份Checkpoint State6严格解析及stateDigest匹配 |
| 副本认证读回 | 未认证401；Result两次字节/ETag一致、严格Result3解析；16份Evidence材料及1份去重审计完整分页读回；Provider硬拒绝，零新增调用 |

## 实际分组、整组回复与审计采用

原始Pass判断共12项，不能拿上轮九项数量作为本轮成功条件。稳定排序按PASS_ORDER/candidateId，此轮RISK组先比较；没有按枚举字母或严重度另定优先级。

- groupIndex0：`ENVIRONMENT_DEPENDENT/RISK`，`c3,c5,c7,c9,c13`，5项、10条重叠边。
- groupIndex1：`STATIC_CORRECTNESS/VERIFIED`，`c1,c4,c6,c8,c10,c11,c12`，7项、21条重叠边。

所有实际组请求的成员、全部原始字段及句柄映射与Pass原判断一致；单一组内键一致。模型可见全Run背景不构成跨组授权，输出仍须精确匹配本组。

| 请求 | 经过 |
|---|---|
| 035 RISK | 五项齐全，但sameRootCause/sameTrigger/sameFix重复出现，违反严格JSON对象键唯一性，JSON_INVALID拒绝 |
| 036 第一次共享纠错 | 五项完整、无重复键、三项true。rationale分别比较同一null-guard根因、外部/未来null调用及未解决环境前提、相容必要修复与可选测试建议，合法整组决策被接受 |
| 037 VERIFIED | 七项实际完整交付，回复只返回c12,c4,c6,c8,c10,c1，漏c11，且重复布尔字段；首先在严格JSON层被拒绝 |
| 038 第二次共享纠错 | 仍重复字段并漏c11，JSON_INVALID拒绝，额度耗尽。未进入Schema/句柄/语义采纳，不将遗漏c11解读为正确排除 |

035/037/038均finish_reason=stop，不是空正文、输出length截断或HTTP故障。普通json.loads可为诊断读出重复键，但生产strict_json/parse_submission拒绝它们是正确行为。本轮没有采用宽松解析或把最后一个值当作有效决策。

RISK组合并依据与采用链：

- DEDUP模型调用 `33b82dba-bea7-4c45-afe1-bff7212bb3c4`（036）。
- SYSTEM审计 Artifact `e3c9d144-37e8-4e08-9f11-37dea92ae22e`，recordType=dedup_decision、recordVersion=1.0，grouping.strategy=compatible-partition-v1，partitionKey为ENVIRONMENT_DEPENDENT/RISK。
- 审计保存原始五项、摘要、完整决策/rationale、重叠边、主项与合并摘要；FULL/字节数/哈希核对通过。合并摘要 `sha256:5f69ce28872c09660c1fb26ba8c3d2e663da1c948c319b2f0fe14785732301e6`。
- Checkpoint revision163保存并精确确认该Artifact引用；离线严格解析与摘要重算一致，Result中的合并Finding与审计投影逐字一致。
- 合并后仍为RISK/ENVIRONMENT_DEPENDENT，没有升级VERIFIED；保留correctness、consistency、security、performance、testing五份贡献，9个Evidence与1个代码引用恰为原成员去重并集；固定主项判断保留。
- 两次共享纠错未按组重置。第二组失败后，第一组已确认合并保留，七条VERIFIED的unmerged投影与原始判断逐字一致，Result limitations如实说明bounded structured comparison rejected。

本轮的正向语义证据仅适用于这五项条件性风险：回复未宣称已发现实际外部调用者，保留相同未解决环境假设，根因/触发/修复论证与原成员相容；同模型判断及有限合成输入局限仍在。

## 报告质量观察：c11待复核

架构候选c11（candidateId `ef7f06b4-1c9e-4cf3-960a-0cf768e2293a`）最终为VERIFIED，claim包含：

> two public static methods ... now have opposite null handling

原始源码：nullableLength直接`text.length()`，requiredLength先`Objects.requireNonNull(text, "text")`。按Java静态语义，null输入下二者均抛NullPointerException；它们是显式校验与隐式解引用的机制差异，不能表述为null接受语义相反。其trigger还把requiredLength Javadoc记为12行，原文实际13行。

验证回复把该claim/factChecks标为SUPPORTED、assumptionsResolved=true，未提供correctedCandidate，原报告继续保留偏差。该观察不否认nullableLength违反原null返回0契约的核心回归，也不把相同位置引用合法当成模型语义正确。保留Finding、验证Artifact与编号源码交设计窗口判断候选质量；本轮不做字符串修补或改写Result。

另：037/038把不在该VERIFIED组的RISK成员作为排除理由，但未解释遗漏c11。全Run背景/通用反馈语境是否造成干扰，现有证据不足以判定因果；不能把这两份不合法回复当作合法的否定或语义正确筛选。

## 证据与离线复现

根目录：`C:/Users/ROG/AppData/Local/Temp/code-weave-b6-partition-real-8yhumign`。

- `real/traces/001..038.request/response.json`：脱敏请求/可见回复、工具调用、finish_reason、Usage及原不可变请求摘要。
- `original-pass-outcomes.json`、`persistent-pass-facts.json`、`candidate-validation-summary.json`：原始12项及验证。c2行为保持重构明确REJECTED。
- `recalled-groups.json`、`partition-request-proof.json`、`dedup-provider-exchanges.json`：分组/成员/每次回复及重复字段、反馈的准确投影。
- `dedup-audits.json`、`readback/authenticated-dedup-audits.json`、原Checkpoint及Artifact：肯定决策和确认后合并回放。
- `architecture-c11-validation.json`、`architecture-quality-observation.json`：c11原始验证、原Finding及编号源码，不更改原件。
- `audit-summary.json`、`guard-summary.json`、`real/result.json`、calls/fees摘要、`readback/summary.json`：执行、资源、费用和认证读回。

从仓库根用 `services/review-harness/.venv/Scripts/python.exe` 执行证据目录的 `audit_run.py`、`inspect_partition_calls.py`，均仅离线读取、回放生产解析/合并与摘要校验，无Provider调用。readback_result.py已在独立副本运行一次，不覆盖既有readback目录重跑。

本轮未重新运行产品测试矩阵、干净环境或Java/UI，不冒称历史回归为当前结果。未读写真实CC、未改产品/设计、未提交推送。唯一真实Run已用完本轮授权；本轮部分目标通过与剩余问题均交设计窗口复核，不再追加付费试错。
