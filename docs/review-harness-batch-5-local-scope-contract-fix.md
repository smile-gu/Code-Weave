# 局部句柄 checkedScope 契约补修

2026-10-13。按真实验收暴露的协议迁移遗漏做最小恢复，不付费、不提交、不推送，不进入批次6。

## 原因与差异

生产 LOCAL_SUBMISSION 漏掉旧 SUBMISSION 的“原样目标文件路径”规则。Schema仍为字符串数组，范围业务门禁仍要求checkedScope为scope.targetPaths子集且不重复；模型连续给路径附行号和说明。不能将该前置失败归因于审查质量或验证矛盾补修。

runtime/review.py提取共同 SCOPE_INSTRUCTION，新旧结构提交指令均要求逐字选择scope.targetPaths的完整文件路径，不追加行号、方法名、说明，不使用目录或排除路径；行范围放codeSelections/证据选择。仅当SCOPE_INVALID的fieldPath为checkedScope时，在已有纠错反馈提供allowedCheckedScope（来自可信固定anchors）和同一规则。完整脱敏拒绝回复、完整请求计量、State保存与预算机制复用。

不更改Schema/路径门禁，不自动裁剪行号，不提高两次纠错额度。其他SCOPE_INVALID原因不被误标为checkedScope错误。原失败Run/Result/Checkpoint不改。

## 红绿与验收入口

新增 tests/integration/test_local_scope_feedback.py，生产create_app＋真实HTTP请求构造＋Provider边界Mock，覆盖错误路径获反馈后修正成功，以及持续错误两次纠错耗尽FAILED+RELEASED且无Result。下一实际请求检查明确规则、原样允许路径、完整被拒提交中的错误路径和脱敏凭据字段；成功Result只有原样路径，一条证据，错误提交未获确认。

实现前2红测失败，揭示生产请求缺少规则；实现后2通过（10.01秒）。不是实际收费模型测试。

## 限制

提示与反馈完整不保证真实模型正确执行。此次没有真实调用，未到达真实独立验证的历史结论不变。Java/UI/Result外部协议不变；未重跑全量/干净环境/Java/UI。旧离线与真实产物保留，当前修改需独立复审，不代表整批通过最终验收。

## 新鲜受影响回归

Python服务目录：
`python -m pytest tests/integration/test_local_scope_feedback.py tests/integration/test_submission_feedback.py tests/integration/test_local_handles.py tests/integration/test_review_fact_correction.py -q --tb=short`

54通过，197.40秒。新增2条包含在54内，不累计56。覆盖局部范围反馈、原Schema反馈、句柄事务与映射、事实冲突和矛盾纠错。Ruff check、两个变更Python文件format --check、git diff --check均通过。主Agent自查确认范围比较门禁未改、可信允许列表取自原anchors、纠错仍使用共享correctionCount。
