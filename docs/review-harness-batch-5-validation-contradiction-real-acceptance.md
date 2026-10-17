# 验证矛盾补修真实对照验收

2026-10-13，单次官方DeepSeek，原冲突合成输入不变。最多8请求、累计8元预算、逐次费用预留；没有自动重跑。

证据：C:/Users/ROG/AppData/Local/Temp/code-weave-real-smoke-qhknbxa9
Run：95f08793-0cf7-4bb0-97b6-8c6c88703e26
实际6请求，全为INVESTIGATION，6条有效Usage，无未知远端结果。费用峰值缓存未命中保守估算0.068470元，累计保守估算2.557840元，不代表控制台实扣。

结果FAILED + RELEASED，FINDING_SCOPE_VIOLATION，无Result。按revision读取Checkpoint最新24，correctionCount=2，Evidence/Candidate Registry均空。观察3次结构提交皆checkedScope无合法原样路径：
1. src/Demo.java diff hunk lines 5-6 (sourceHandle s2)，以及glob/search描述。
2. src/Demo.java diff lines 5-6 (sourceHandle s2)；src/Demo.java final line 1 (sourceHandle s3)。
3. src/Demo.java diff side lines 5-6 (sourceHandle s2)；src/Demo.java final side line 1 (sourceHandle s3)；src/Demo.java target path in scope。

两次反馈均stage=SUBMISSION、reason=SCOPE_INVALID、fieldPath=checkedScope。输入Schema允许字符串列表，业务门禁要求原样targetPaths。代码核对发现旧SUBMISSION明确说明checkedScope必须逐字复制scope.targetPaths，但生产LOCAL_SUBMISSION没有这条明确说明，仅要求actual checked scope；当前反馈也只给泛化原因。这是已确认的平台协议表达差异，可能解释模型持续添加描述，不能仅归因于模型不服从已明确给出的规则。

此次在独立验证前停止，VALIDATION_CONTRADICTION未被触发，因此真实矛盾纠错目标未验，不是补修失败证据。未发生压缩，无有效Finding；保留原离线52/独立2通过结论，不用本轮替代质量验收。

建议设计窗口核对最小协议补修：LOCAL_SUBMISSION明确checkedScope仅原样目标文件路径；SCOPE_INVALID反馈提供可信允许路径及位置说明，沿用原两次纠错，不放宽范围校验、不自动规范化模型文本。本次仅记录证据，没有实现修改，也未付费重跑。

只读Checkpoint投影追加为checkpoint-observation.json，原数据库不修改；未提交、未推送，不进入批次6。
