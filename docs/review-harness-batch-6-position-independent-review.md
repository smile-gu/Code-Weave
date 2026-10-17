# 批次6 位置协议及真实结果独立复审

2026-10-16 19:18 +08:00。基线30a0054加当前未提交JSON/资格及POSITION_V1实现。只读复核产品代码和真实原件，新增本复审文档；无产品改动、付费、CC、提交或推送。

## 结论

位置引用切片复审未发现阻断性问题，真实六Pass成功证据成立；不能把该结论扩大为报告质量无问题。整批质量仍有一项P2：跨Pass语义重复未进入去重，且放宽召回后仍会被合并阶段的文本相等条件阻止。该问题在既有dedup实现中，不是POSITION_V1引入的引用退化。

## P2：语义去重被三项文本相等限制替代

位置：runtime/dedup.py:43-45及64-72，调用入口runtime/multipass.py:_deduplicate。

真实Result五条Finding均描述nullableLength移除null保护、null触发NPE而非返回0；同一修复方向被不同Pass改写。生产recall_groups对规则做大小写/空白归一化后仍要求完全一致，因此返回0组，直接跳过语义比较。即使修正召回，merge_group又要求trigger和suggestion归一化全文一致；模型确认sameRootCause/sameTrigger/sameFix也不能克服该限制。不能只放宽rule召回后声称去重完成。

本窗口新鲜离线信号：从原real/result.json按MultipassFinding严格解析，执行生产recall_groups，打印findings=5、recall_groups=0，断言存在组失败。十对来源overlaps均true，排除来源不相交。仅在内存统一rule后得到一个5成员组；执行merge_group仍抛Different triggers or fixes cannot merge。原Result、数据库及模型判断未改。这个内存对照只定位第二道门槛，绝不是批准统一不同语义或重写原判断。

建议最小方向：将结构化来源接近作为有界召回条件，不把措辞一致当同根判断；语义比较仍须核对根因/触发/必要修复，并保留全部贡献身份及原判断。源码重叠不代表同Bug，必须保留同位置不同根因/触发/修复的反例。合并主判断沿既有确定性规则，禁止创造新Evidence或改级。需先冻结召回规模、语义子组和合并安全规则，再交开发；本复审不直接授权实现或付费。

注意本次5条Finding共有10个Evidence，尚未超过既有单Finding16个Evidence上限；不得为了大组去重静默丢弃来源或随意扩大合同容量。超过预算/容量或不确定时保留独立项并说明限制。

## 位置协议复核

静态核对domain/position、application/position_validation、runtime/review、State读回、Context及Compactor版本路径。新旧协议显式区分；平台枚举候选叶子并从候选授权Evidence安全材料提取完整行，再进入原内部门禁。保留逐项位置绑定、原文、版本及Artifact/Checkpoint确认路径。CORRECTED按对应字段比较，不因原文在其他历史字段出现误拒绝。未观察洞、半行、哈希/来源/候选隔离及脱敏规则未移除。

新鲜独立运行：

```powershell
# services/review-harness目录
.venv/Scripts/python.exe -m pytest tests/unit/test_position_validation.py tests/unit/test_position_schemas.py tests/integration/test_position_review.py -q --tb=short
.venv/Scripts/python.exe -m ruff check review_harness tests
.venv/Scripts/python.exe -m ruff format --check review_harness tests
git diff --check
```

57 passed，102.54秒；Ruff及141文件格式通过，差异检查通过（Git换行提示不等于失败）。未独立重跑709全量/干净环境/Java/UI，不用阶段694或开发81项冒称当前最终全量。

真实证据根C:/Users/ROG/AppData/Local/Temp/code-weave-b6-position-real-qmb0wiq8。独立核对78份产品文件SHA全部与production-digests.json一致；原Result SHA256为7163b2e9c9f7e3f8e9f30df295b6f5b416db11e7c3597b5955691c36a9c268ae，与交付记录一致，Result的五条Finding通过严格模型解析。33次/保守¥0.567602、24条绑定回放及账本清零是开发提供证据，本轮核对记录但未重新执行整套认证读回/账本审计，不冒称独立重做。

## 摘要行号偏差

原Result的architecture claim仍写Javadoc在final第7行；其他验证文本写第8行。位置绑定合法不保证自由文本准确。本次已确认的源码null回归不依赖该行号描述，不能因此宣布原文绑定失效；也不能把错误文字称为正确。

建议展示和跳转统一从平台结构化位置渲染，避免把模型摘要内的数字当可执行引用。若进一步自动纠正自然语言中的数字，应另行设计，不能用全局替换或关键词过滤解决。本轮不为文案偏差反复付费。

## 下一步

先将位置协议与语义去重问题分开管理。位置切片可作为通过的局部成果，整批质量不因此全部放行。后续优先离线冻结并补修去重，基于本次原结果及同位置不同Bug反例回归，不继续付费探索。真实CC、下一Run、提交及推送均需用户另行决定。此前日志历史字节异常不宣称恢复，本轮不重写该历史文件。
