# 批次6：验证位置引用协议冻结与开发交接

状态：设计冻结，待开发。2026-10-16。用户同意以位置引用替代模型抄写候选/源码，本窗口仅落盘设计，不代表实现已完成。不付费、不运行CC、不提交推送。

## 目标与依据

真实复验29次请求后在architecture验证失败：factChecks[1].candidateText以省略号拼接不连续候选文字；sourceQuote本身有效。该Pass此前调查Schema纠错已用完两次，验证首次失败即停止。不是“源码必须全文抄写”，也不能宣称本次验证收到过两次纠错。

当前LocalFactCheck同时要求candidateHandle、sourceHandle、candidateText、sourceQuote；resolve_validation及_validate_corrections先后校验引用。模型承担了不必要的原文复制工作。改为模型选择位置、平台从不可变材料解析原文；保留语义判断、身份与观察范围门禁。本变更不增加纠错额度、不修改共享额度政策。

## 冻结协议

仅升级模型侧局部验证协议。新旧版本显式区分，由请求构造时确定并在Checkpoint恢复中一致；禁止按字段猜版本或新旧字段混用。旧协议严格读取、旧Checkpoint可恢复，不能把历史失败重新解释成成功。版本标识的具体落点沿用仓库现有协商机制；如需扩State必须升级并提供迁移/严格拒绝测试，不静默扩展旧Schema。

新factCheck示例（设计字段，当前尚未实现）：

```json
{
  "candidateHandle": "c3",
  "candidateField": "/claim",
  "sourceSelection": {"sourceHandle": "s7", "startLine": 20, "endLine": 25},
  "outcome": "SUPPORTED",
  "explanation": "该路径直接解引用可能为null的参数，未保留原有保护。"
}
```

candidateField为平台在验证请求中列出的完整JSON Pointer，指向候选原始版本的一个非空字符串叶子；允许范围仅为当前旧校验遍历的title、claim、category、suggestion及reasoningSummary的字符串叶子（数组下标必须存在）。不得引用身份、权限、引用字段或任意JSONPath。无需新增主张拆分器/句子编号/字符偏移。每次检查指向一个完整字段；长字段内部关注点写在explanation，不要求再抄句子。平台向模型列出合法指针及对应文本，避免模型猜字段。

sourceSelection复用SourceSelection结构，但本协议必须同时提供startLine/endLine；一基、闭区间，不允许仅填起点。sourceHandle绑定Run/Snapshot/来源版本及side/哈希，模型不提交本机绝对路径。由平台渲染展示路径、side、行号及可回读材料。

坐标沿用来源自身物理坐标：base/final为对应源码文件行；diff为Diff文本物理行，不是hunk里的新旧源码行；ARTIFACT为Artifact正文物理行。验证请求必须明确展示coordinateKind，禁止猜测转换。源/目标文件行与Diff行的转换只能使用既有可靠解析器，不在本切片新增映射规则。

## 确定性解析与安全门禁

1. candidateHandle必须等于当前验证候选，candidateField对原候选版本解析，不能指向correctedCandidate或别的候选。
2. sourceHandle必须属于该候选已确认Evidence的来源；同Run其他候选/Pass的可见来源不因此获授权。保持既有Pass与候选专属来源校验。
3. 区间必须完全落在已观察且该候选Evidence授权的材料内。允许合并相邻完整观察区间，不得跨未读空洞；模型没读过不能引用。
4. 保留脱敏、哈希、半行和来源完整性规则。端点或内部含不完整物理行时拒绝，不从隐藏全文补齐未观察字符。坐标解析后必须按原证据的安全可见正文取文，不能从实时工作区读文件、泄漏脱敏前内容。
5. 平台提取candidateText/sourceQuote并沿用内部FactCheck及原校验。不允许省略号拼接、模糊匹配、模型提供备用quote或越界时自动扩大范围。选文超过现有Text上限4096字符时明确返回范围过大，要求缩小源码区间；候选叶子沿用其既有上限，禁止静默截断。
6. 同一sourceHandle命中多个Evidence时，选择包含完整区间的授权材料；若绑定版本/内容存在歧义则拒绝，不能仅凭文字偶合选择来源。解析产生明确evidenceId/sourceIndex，原内部引用门禁不放宽。
7. userQuote暂保留原协议的可选字段与严格校验，不把USER_INPUT变成实现来源。本切片只替换候选文本与CODE/ARTIFACT抄写，不扩用户文本坐标协议。

## 事实修正兼容

SUPPORTED/CORRECTED/CORE_CONTRADICTED/UNRESOLVED及disposition语义不变。CORE_CONTRADICTED必须拒绝，UNRESOLVED不能VERIFIED，事实正确不等于缺陷成立。

CORRECTED按candidateField对齐比较原候选与修正候选对应叶子，必须提供实际改变的非空文本；身份、证据与源码引用仍不可改。不再用“原字段全文是否出现在修正对象任意位置”判断是否修正。多个检查指向同一字段可保留；不可因此推定字段内每个事实已核实。语义上是否充分修正仍由模型负责，不新增确定性语义分类器。

持久化时保留位置绑定及平台提取结果以供回放；若现有内部FactCheck无法容纳位置，优先在版本化验证记录/诊断投影中保留，不修改外部Result 3.0。不得只记录位置却无法重建原绑定。新Checkpoint与旧记录恢复路径须明确测试。

## 最小实施范围

- domain/handles与对应模型输出Schema：新局部验证结构及严格版本处理。
- application/local_handles：合法候选字段投影、来源坐标说明、严格区间解析、内部FactCheck转换。
- runtime/review：新协议提示词、按字段修正判断、具体有界脱敏诊断；保留旧协议校验路径。
- Checkpoint/验证记录：仅必要的协议身份和位置回放信息；不得为此次调整重构执行器/账本/多Pass编排。
- 不改外部Result/Java/UI合同、不改Evidence提交协议、不调整纠错次数/费用/调用上限、不自动付费验收。

## 离线验收与交付

先补红测再最小实现，必须覆盖：

- 本次失败的脱敏候选用字段引用成功解析；无须模型抄写候选/源码。旧省略号回复仍按旧协议拒绝。
- 候选指针缺失、非法字段/数组下标、跨候选/Pass/Run、来源版本和哈希错误均拒绝。
- base/final/diff/Artifact各自坐标；多文件多hunk、目录文件转换已有回归不退化；半行、越界、未读洞、授权Evidence子区间、输出超限均拒绝。
- CODE/ARTIFACT不能由USER_INPUT替代，脱敏后不会回填秘密；模型不得混交quote/旧字段。
- public→默认权限修正继续有效；未改变对应字段、核心反例、矛盾判断及良性REJECTED仍被正确约束。
- 多个来源映射、原子提交、取消、Checkpoint失败/重启恢复、新旧协议及Result兼容。

交付列明改动与新鲜命令/计数、版本兼容策略和限制；执行受影响专项、Schema、Ruff/格式及差异检查。全量按实际影响决定，不冒用历史计数。问题日志仅追加且不入Git。先交设计窗口独立复审，再另行授权一次真实复验。通过标准不等于报告非空：真缺陷有依据、良性不误报、位置可回放、最终Result与资源/账本闭环均需区分核对。

## 给开发窗口的提示词

请按本冻结稿实现“模型选择位置、平台确定性取文”的最小协议升级。先阅读现有局部句柄、来源观察范围、factChecks、事实修正和Checkpoint版本实现；不要简单删除quote校验，也不要让模型手填绝对路径。先给出现有机制对应的版本落点，再红测和实现。新旧协议严格区分，内部保留确定性原文与位置回放，外部Result 3.0不变。重点证伪未读范围、半行、跨候选/Pass、Diff坐标、脱敏和CORRECTED按字段对齐。保留两次共享纠错与所有资源/费用门禁。本轮只离线开发验收，不读密钥、不付费、不运行真实CC、不提交推送；完成后交设计窗口复审。
