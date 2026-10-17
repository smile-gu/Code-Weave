# 批次5：兼容型预算离线交付


## 2026-10-12 兼容型 Token 预算（冻结稿40）

此项替代任何输入低估立即全局阻断的旧规则。DeepSeek 策略 deepseek-compatible-budget-v1：完整请求 E，U=max(1024,ceil(E/10))，C=E+U；C≤27648 才发送，软水位比较 C。所有真实调用目的共用策略，Run 内不按 Usage 学习或放宽。

E<A≤C 告警 INPUT_ESTIMATE_UNDERCOUNT；C<A≤27648 告警 INPUT_ESTIMATE_RESERVE_EXCEEDED。告警绑定 ModelCall 并发布认证可读 SYSTEM Artifact，保存 E/C/A/差值/版本，不复制模型正文。A>27648 或实际总量>32768 时先保存 Usage，再停止，不执行返回工具。旧 PROVIDER_TOKEN_METER_DRIFT 字节保留，但不再毒化 Readiness；坏资产、资源 UNCERTAIN 等保护保留。

真实 Context Manifest 写严格 1.1（新增 C/U/policyVersion），1.0 历史严格读取；ModelCall v1 新字段可选，历史缺省字段不补 null 重写。Result2.0 与 Java/UI 报告合同未变。没有已确认稳定结构化超窗错误码，生产恢复 DISABLED，仅严格分类接口 Mock；HTTP400/超时不自动重发。余量是工程策略，不保证误差最坏界；Tokenizer/DSML 不改。

本轮只离线，具体新鲜证据见 docs/review-harness-batch-5-compatible-token-budget.md；旧全量、Java/UI 与真实验收不能代替本轮证据。

## 验证记录

首轮受影响矩阵：140通过、1失败，254秒；旧摘要测试期望保存仍超预算视图，与冻结稿冲突。修正为拒绝替换并保留旧视图。最终专项（预算、摘要、ModelCall）：50通过，26.11秒。Ruff、格式109文件与差异检查通过。其余受影响矩阵正在完成，最终结果追加。

红测：7897→7975 原漂移规则失败，修后告警与原工具ID续接通过；不增加固定78。新增整数ceil/区间/三种调用目的零发送、Manifest版本与Schema、历史漂移重建App健康和历史字节保留。

恢复能力未启用，真实模型效果未验；本轮不改Evidence/factChecks、取消和Run终态事务，不调用API、不提交、不推送、不进入批次6。最终Python全量/干净环境仍待提交前补齐；此前Java/UI证据保留，内部Manifest版本变化不改变其Result输入。

## 最终新鲜受影响验收

2026-10-12，本地 Python 3.12 / .venv。全部为离线 Mock，无付费请求。

- `python -m pytest tests/integration/test_compatible_token_budget.py tests/integration/test_compaction.py tests/integration/test_model_calls.py -q --tb=short`：50通过，26.11秒。
- `python -m pytest tests/integration/test_real_schema_fixtures.py tests/integration/test_real_enablement.py tests/integration/test_real_context.py tests/integration/test_deepseek_adapter.py tests/integration/test_provider_http.py tests/integration/test_real_review.py tests/integration/test_submission_feedback.py -q --tb=short`：93通过，220.76秒。
- 自查补上Adapter直接invoke的C门禁后，`python -m pytest tests/integration/test_compatible_token_budget.py tests/integration/test_deepseek_adapter.py -q --tb=short`：66通过，19.44秒，包含新增零HTTP发送用例。与前两组重叠，不相加冒充独立计数。
- `python -m pytest tests/integration/test_lifecycle.py::test_uncertain_resources_block_only_associated_session_until_confirmation tests/integration/test_deepseek_meter.py -q --tb=short`：4通过，2.33秒。
- `python -m ruff check review_harness tests`、`python -m ruff format --check review_harness tests`：通过，109文件格式符合。
- `git diff --check`：通过。另一次临时禁用autocrlf的检查把CRLF误作尾空白，未修改系统/Git配置；最终使用仓库原配置核对通过。

可复现路径及变更：runtime/token_budget.py策略与严格错误码分类；deepseek.py发送/响应门禁；context.py Manifest1.1、持久告警；compaction.py C水位与替换前硬预算；ModelCall及store旧字节兼容；readiness.py移除历史漂移门禁；bootstrap/config/profile策略验证；新Manifest Schema和导出入口。未修改Tokenizer、Evidence、候选、Result2.0、Java/UI实现。

恢复DISABLED：没有稳定Provider结构错误码依据，不宣称单次超窗恢复已上线。余量不是数学误差保证。真实7897→7975样例仅离线重建验证，原付费记录未改。告警持久确认失败沿用停止路径，不允许返回工具继续；取消和未知HTTP结果沿用无自动重发/资源保护。建议设计窗口独立检查此边界及认证诊断读取。

最终Python全量/干净环境未运行；原Java/UI/全量证据仅历史保留。本次交付供离线独立复审，不能据此批准整批提交。


## 2026-10-12 21:20–21:22 +08:00 一次真实确认（用户另授权）

根：C:/Users/ROG/AppData/Local/Temp/code-weave-b5-compatible-confirmation-a6h4knf8/compaction；Run bceee1c2-3168-4b7a-9fe1-fef43e3765ff。官方价格再次核对，单目标最多16请求，新最坏预留1.409024元，历史保守1.639872元，管理上界3.048896元。生产Adapter与公开六文件输入，未更改水位/预算/权限/注入回复。脚本run_compatible_budget_confirmation.py在仓库外，原验收产物保留。

真实事实：16请求（14调查、2摘要）全部有效Usage且本地关闭，无未知收费。2个FULL Summary经hash核对，Checkpoint实际替换到summaryVersion1/2；第一次后工具计数由14增至29，续接实际执行。第二次后实际请求hash只读重建一致，ProviderRequest严格配对通过，原工具结果3条保留；同调查模板估计20393→10966下降。实际A11044较E10966低估78、C12063，在预算内持久UNDERCOUNT告警，FULL诊断产物hash有效，ModelCall无failureCode，无永久漂移熔断。

最终目标未通过：返回工具批次使累计工具数量将超过32，报CONTEXT_TOOL_CALL_LIMIT_EXCEEDED；FAILED+RELEASED，没有Result（Result表0行），执行目录无残留。9条Evidence已持久；源码review.py的BUDGET_ERRORS列举模型次数、请求预算、图步、摘要次数，遗漏工具次数，故不能进入既有INCOMPLETE收尾。这是平台预算耗尽收尾缺口，不能把本次失败称为完整验收成功。剩余补修应先离线回归验证有效Evidence下工具预算耗尽应诚实INCOMPLETE，以及无有效材料不得假成功；不得通过增加32工具上限或自动重跑制造成功。本次未修改生产实现。

费用：本轮Usage按峰值cache-miss价格上界0.453136元，累计保守上界2.093008元；不是控制台实际扣费。16/16有效Usage，无需因未知费用再询问账单。停止，无自动重跑；下一步交设计窗口核对acceptance-verification.json和收尾补修方向。批次5仍未达到整体提交条件，未提交、未推送、不进入批次6。


## 2026-10-12 用户授权工具预算拓宽

用户明确要求拓宽工具预算并再次尝试。新增graph_max_tool_calls配置，默认32，严格范围1–64；本次独立验收显式64。真实State2.0计数和Provider映射最大64并同步共享Schema；身份唯一、配对和实际计数一致性仍严格校验。运行Context按配置在返回后拒绝超限批次，不执行超限工具。其他32约束（候选、分页进度）不扩大；模型16次、Token/费用/取消/资源保护不变。不是无上限工具调用，也未修复已有工具预算耗尽INCOMPLETE收尾缺口。

新鲜离线：test_real_context.py与test_real_schema_fixtures.py共26通过，28.66秒。覆盖31+2在32阻断/64放行、63+2在64阻断、Usage超预算仍拒绝，以及原ProviderID续接和Schema一致性。Ruff、109文件格式和git diff --check通过。Python全量/干净环境未重跑；旧26以外矩阵是历史证据，State2.0 Schema变化需设计窗口复审，不直接批准整批。

真实确认单目标一次最多16请求，官方价格核对；新增最坏预留1.409024元，先前保守费用2.093008元，累计管理上界3.502032元，低于用户授权8元。产物根C:/Users/ROG/AppData/Local/Temp/code-weave-b5-tools64-confirmation-3qf1sqtr/compaction。结果待完成后追加。未提交、未推送、不进入批次6。


## 2026-10-12 21:43–21:44 +08:00 工具64一次真实重试结果

Run 0e9ea482-179d-4055-9901-d33a47ebc4c8，11请求全部有效Usage（10调查/提交、1摘要），无未知结果/自动重发。峰值cache-miss费用上界0.328630元；累计保守2.421638元，不等于控制台实际扣费。

SUCCEEDED+RELEASED，Result2.0不可变hash与持久document_bytes、读回JSON和生产DTO核对通过；Result hash sha256:4ab7cc469fffe3bbefc606f28ac842ac8b389e48ecba80584b44d61cedf2418f。报告PARTIAL/INCOMPLETE，9条Evidence、0 Finding、1未验证候选，原因CONTEXT_REQUEST_EXCEEDS_BUDGET/CANDIDATE_VALIDATION_INCOMPLETE/SOURCE_COVERAGE_PARTIAL；没有付费VALIDATION请求，不得宣称种子Bug识别或独立验证完成。

1个FULL Summary hash核对通过，Checkpoint revision39→40替换summaryVersion1，最终revision47。实际压缩后结构提交请求只读重建hash完全一致，严格ProviderRequest通过，同模板E21065→9621。模型在压缩后提交，未再执行工具，最终工具计数13。因此本次没有再次触发32上限，也没有实际跨过32；不可把成功归因于64预算，或声称本次单Run覆盖压缩后的新工具执行。此前确认Run已有实际压缩后工具执行证据，但最终没有Result，两Run证据应分开。

执行目录无残留；本次诚实INCOMPLETE Result交付成立，真实完整Finding/独立验证目标未达到。此前工具次数耗尽未进入INCOMPLETE的收尾缺口仍未修复（即便提高64也可能再触发）。停止继续付费；新配置/State Schema范围变化需独立复审，未提交、未推送、不进入批次6。复现与核对：仓库外run_tools64_confirmation.py、reconstruct_confirmation_offline.py、acceptance-verification.json，根code-weave-b5-tools64-confirmation-3qf1sqtr/compaction。
