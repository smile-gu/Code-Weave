# 局部范围与矛盾验证真实验收

2026-10-13 16:17:13–16:17:38 +08:00。相同public冲突合成输入，官方DeepSeek，最多8请求，累计8元预算逐次预留。没有修改生产实现或注入回复，没有自动重跑。

证据目录：C:/Users/ROG/AppData/Local/Temp/code-weave-real-smoke-ebnedhfp
Run：cc33d3cd-06e9-4f5b-9e0e-412984f0da0c
Result：df0074e2-211b-468a-80b9-0396fd36da0f

## 实际结果

5请求、5条有效Usage，3调查+2验证；输入15392、输出2771 Token，峰值缓存未命中保守费用估算0.052952元；累计保守估算2.610792元，不代表控制台实扣，无未知Usage。SUCCEEDED + RELEASED，COMPLETED/FULL，3 Evidence、1 VERIFIED Finding，0未验证候选，无摘要。

checkedScope首次使用原样src/Demo.java，范围门禁通过，不需要范围纠错。同批n1/n2/n3与候选确认，Registry有e1/e2/e3、c1，外部Result保持真实UUID。

独立验证首次VERIFIED+counterexampleHolds=true，平台确实抛VALIDATION_CONTRADICTION并给完整脱敏拒绝回复/原材料及反馈；第二次仍VERIFIED但counterexampleHolds=false，重新说明“非null输入、外部异常捕获”不推翻此方法对允许null输入违约的Claim。Checkpoint按State.revision选择最新18保存两次完整验证和反馈，correctionCount=1。非平台自动翻值。最终Finding来自第二次真实验证，核心null回归结论有原源码和Diff支持。

因此：原样范围协议真实闭环通过；矛盾验证有界纠错真实触发并成功；句柄→Evidence→独立验证→Result闭环通过。

## 剩余失败目标

事实冲突修正仍未通过：Finding.reasoningSummary.trigger及Markdown保留Calling the public method Demo.value(null)，原源码int value(Integer x)无public，是package-private。最终验证三条factChecks只核对nullguard/源码内容/null异常，没有核对该权限描述，correctedCandidate为空。因此不宣称事实核对质量或整批最终验收通过，不仅凭VERIFIED认定报告完全正确。原不可变Result保留，禁止手工修正文案掩盖偏差。

## 本地核对与证据限制

生产ReviewResult DTO及Result规范序列化sha256与Run摘要匹配；3份认证Evidence材料已读回；原样scope、Registry绑定、两次验证true→false、correctionCount=1已离线断言。首次辅助核对误用内部ReviewPackage DTO，之后纠正为外部ReviewResult；筛选消息一度把反馈fieldPath误当validations回复，修正为解析对象存在validations键后通过。这些本地核对错误不触发任何收费重跑，也不更改产物。

本轮没有Java/UI或全量/干净环境测试，不认证压缩目标，不证明模型稳定性。新增checkpoint-observation.json为只读持久State投影，原数据库/Result不改。

建议交设计窗口复审此次两个目标通过及剩余权限描述遗漏，依据已有完整验证记录决定下一步最小事实核对补修，不自动继续收费。未提交、未推送，不进入批次6。
