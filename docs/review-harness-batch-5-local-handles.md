# 批次5局部句柄协议离线交付

实施基准：仓库外43-local-source-evidence-candidate-handles-freeze.md（2026-10-13）；仅本子项，不付费、不提交/推送、不进入批次6。

## 交付行为

来源s、确认Evidence e、候选c在Run内平台顺序分配，不重用；n只在本次结构提交内有效。真实执行入口固定State3.0，严格HandleRegistry绑定runId/snapshotId、来源身份/观察区间、e绑定原选择、c绑定UUID；旧State2.0严格读取，Fake State1.0不迁移。新Run零继承，Checkpoint freshness禁止旧Run收费重放。

调查使用LocalInvestigation：NEW/n与SAVED/e明确判别，允许COMPLETE同批新证据+候选。模型选已展示sourceHandle及区间，不复制UUID/hash。平台读原材料并prepare全部Evidence、验证全部Candidate，构造完整候选State/回执，再EvidenceStore.save_batch，之后Checkpoint确认。语义预校验失败无新Evidence写入；持久失败不进入纠错。Evidence事务与Checkpoint不是原子事务：Lost ack仅在原UUID及内容/精确Checkpoint一致重读后采用；State失确认停止，可保留已写Evidence，没有重新分配UUID或假回执。

ModelCall UUID、提交digest与Checkpoint revision在内部receipt可追溯；模型回执只呈现n→e与c。新协议全局最多2次纠错沿用，实际下一请求保留完整脱敏被拒提交、具体反馈与当前允许句柄。致命完整性、存储和取消不作为语义纠错。

来源观察：直接完整read_file行及未截断search单行可授予范围；Diff使用完整固定Diff物理LF行，剔除半行和非代码元数据；Artifact使用存储内容行坐标。首尾半行只在正确Cursor连续拼接完成时补授该行。转存预览不给未展示正文；连续读完可信captureComplete的read_file Artifact后才授予其原CODE范围。选择FULL仍在EvidenceService原始材料入口重新核对脱敏/覆盖；文件Inspection沿用原规则，不由证据片段FULL推导全文已查。

候选codeSelections须在其选用Evidence的同一s来源内。为保持Result既有“CodeReference必须精确属于Evidence”的规则，平台规范化到最小的已授权包含区间，绝不扩大到未展示范围。不添加跨Pass业务去重引擎。候选事实核对提供c专属e/s与实际原文；同Run其他来源不能因Registry存在而被用来验证该候选。一个s关联多个Evidence时，仅在该候选自身Evidence的原文中按平台顺序选第一个匹配quote的槽，映射为内部evidenceId/sourceIndex。

factChecks输出c/s，平台转换为原CandidateValidation后再过原缺核对/摘录/错误前提/修正落实/量化和claimKind门禁。correctedCandidate身份、passId、claimKind、evidenceLinks、codeSelections不能改变。Final Finding仍是平台UUID及原CodeReference/hash，平台渲染真正修正后的内容，Result2.0与Java/UI不新增句柄字段。

压缩继续纯文本；Registry只随平台State保留，不从摘要s/e/c文字创建映射。当前模型视图展示候选所需Evidence、最近16条Evidence/来源及用户来源的子集；持久Registry不裁剪。完整视图、材料、Schema、反馈计入原256K E/C预算；Registry/State/Checkpoint/输出限额不扩大。

## 文件与协议

新增domain/handles.py、application/local_handles.py、application/handle_observations.py。CandidateSemantics复用既有语义字段，内部身份仍由CandidateFields承载；生产bootstrap/context/review/checkpoint解码接通State3.0，原EvidenceStore事务复用，无新事实表/迁移/工具/Provider。

新严格Schema：review-graph-state-v3、review-handle-registry-v1、review-local-investigation-v1、review-local-validation-v1。内部正反例独立放contracts/fixtures/review-local-handles-v1，公共review-real-v1不混入Java未知协议名。Python元校验、DTO正反例与导出一致性覆盖。Result2.0/State2.0因共享语义字段复用导出字段顺序有变，字段/约束不变；Java公共合同专项复验。

## 验证与证据范围

- 首个来源句柄测试在实现前因缺handles模块红测，最小注册/范围解析后通过。
- 新State3生产组合：同批n/Candidate→Evidence→独立验证→Finding；原Provider tool_call_id保持。普通小输入仅4模型请求，无单独获取Evidence UUID轮次。SAVED跨轮、连续Artifact分页与两次压缩稳定映射、重建fresh State禁止重放。
- 事务注入：第二证据/候选错、重复n/链接、未展示范围、脱敏FULL均无本批写入；Evidence回滚、提交丢ack、Checkpoint丢ack及未确认分别验证，没有验证请求/假回执。
- 原事实冲突19组已迁移到真实State3入口，覆盖非核心修正、核心反例、未知/跨候选来源、修正越权、坏摘录、量化降级及两次纠错。
- 旧原始UUID协议测试仍保留：tests/legacy_runtime_fixture.py显式使用离线State2 fixture，测试名称标记legacy；这些不认证当前模型句柄协议，也不会在生产入口或重启恢复中启用State2。初次广矩阵18个旧Mock因协议升级失败，未通过放宽生产协议解决。
- 新句柄Schema最初混入公共Java fixture清单导致Java拒绝未知内部协议名；已将内部fixtures分开，未修改Java白名单或产品代码。

最终新鲜计数完成后追加。此轮没有Python全量/干净环境或Java/UI全矩阵；受影响证据与历史矩阵区分。旧真实Result/失败产物不修改。

## 剩余限制

本轮只有离线Mock，不证明真实模型漏报/误报或协议负担实际改善；需后续另授权有限真实闭环。引用合法不等于语义判断正确；256K/字节/来源64/Evidence128/Candidate32等限额仍可能停止大任务。没有重启补全Evidence已提交而Checkpoint未知的Run；它保留事实但不收费重放。服务/平台不向模型承诺两个存储原子。整批未批准提交。


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
