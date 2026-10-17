# 验证矛盾最小补修交付

依据仓库外46-local-handles-real-validation-recheck.md，2026-10-13；仅离线补修，无付费调用、提交、推送或批次6开发。

## 定位与更正

只读扫描原cp.sqlite，并按State.revision取最大值，核对revision26：成功验证完整回复已保存，VERIFIED与counterexampleHolds=true同时出现，assumptionsResolved=true。模型将证实null Bug的触发样例称为成立反例；平台_finish静默跳过。上次按随机checkpointId排序误读revision24造成错误归因，验收文档已追加更正并保留历史判断。原Result/Checkpoint未改，新投影validation-revision-26-observation.json仅用于核查。

## 最小实现

runtime/review.py在现有验证语义门禁检测VERIFIED + counterexampleHolds=true，抛现有SubmissionIssue：reason=VALIDATION_CONTRADICTION，stage=VALIDATION，fieldPath=validations[index].validationSummary.counterexampleHolds，外层code沿用PROVIDER_VALIDATION_INVALID。复用完整脱敏拒绝回复、反馈与全局两次纠错/模型请求预算。针对矛盾反馈说明反例须推翻Claim，支持Bug的触发样例不是反例，要求基于原材料重审而非机械翻转布尔值。

不改Schema/句柄/引用/存储/取消/资源门禁。REJECTED+成立反例仍拒绝；RISK等其他组合原行为保持，不猜测模型意图。额度耗尽返回失败，不静默生成COMPLETED零Finding。本次历史Run已耗尽两次额度，不能声称它能经补修转成有效Finding。

## 新鲜测试

通过生产HTTP入口及外部Provider Mock编写两条回归，修改实现前2失败：修正路径缺Finding，持续矛盾路径错误SUCCEEDED。最小实现后2通过（11.49秒）。修正测试同时检查下一实际请求保留完整被拒回复及具体矛盾反馈，并交付修正后的VERIFIED Finding；持续矛盾验证最多3次验证请求（原调用+2纠错）、FAILED+RELEASED且无Result。

命令（Python服务目录）：
`python -m pytest tests/integration/test_review_fact_correction.py tests/integration/test_local_handles.py tests/integration/test_submission_feedback.py -q --tb=short`
52通过，191.66秒，包括REJECTED核心反例、事实修正/身份篡改、句柄事务和完整反馈。红绿2条包含在52中，不加总。

Ruff check、两文件format --check、git diff --check通过。未跑全量/干净环境/Java/UI；本次改动影响Python验证逻辑，旧全量不认证当前新增分支，Java/UI外部契约未变，历史证据保留。

## 剩余限制

这是协议一致性保护，不证明模型正确识别反例、权限或必要假设，也不会纠正语义一致但实质错误的判断。真实模型尚未验证本次纠错；此前public事实混淆仍是质量限制。先交设计窗口独立离线复审，不自动付费重试。
