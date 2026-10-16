# 批次6：去重兼容性预分区离线开发交付

交付时间：2026-10-16T21:15:43.873943+08:00。基线 `6ef9a018a06f50b0acb1ae437cf3a8833edc41ec`。依据：[兼容性预分区冻结稿](review-harness-batch-6-dedup-partition-freeze.md)。本轮离线开发完成，待设计窗口独立复审；未付费、未读密钥、未运行真实CC、未提交或推送。

## 最小实现

产品只修改 `runtime/dedup.py::comparison_groups`：

1. 使用原始持久化 Finding 的精确 `(claimKind, disposition)` 分区，再在分区内调用原 `recall_groups` 做来源重叠连通召回。不同分区不能充当桥，不按severity/category/关键词/新模型标签分类。
2. 32项分块和块内重求连通分量仍在分区内执行。最终组按最早成员 `(PASS_ORDER, candidateId)` 排序，组内沿用同一顺序，输入乱序与枚举字母顺序不影响预算优先级。
3. grouping 增加 `strategy=compatible-partition-v1`、`partitionKey={claimKind,disposition}`、全局稳定 `groupIndex`。原component/block/overlapEdges保留，既有SYSTEM审计直接持久化该投影，不扩State/Schema。

兼容仅代表允许比较，不代表同根；模型仍须对本组所有成员确认根因、必要触发和必要修复。原精确句柄门禁、`merge_group` 本地兼容复核、固定主判断、全部来源/贡献、16/16/32和字节容量、FULL/哈希/Checkpoint确认及未知保护均未修改。两组不会递归合并。执行器、费用账本、通用纠错反馈、Active pass指令、POSITION_V1、State5/6、Result3和Java/UI没有改动；仍共用10次/600秒与两次共享纠错及全Run预算。

## 红绿与正式聚合证据

- 新增 `tests/fixtures/dedup-real-nine-findings.json`：九项字段逐项等于上次真实 `original-pass-outcomes.json`，同时保留其SHA及三份原始失败回复；没有统一措辞、修改判断或替换旧五项夹具。
- 第一个正式红测在旧实现得到 `[9]`，期望 `[5,4]` 而失败；最小实现后变绿。补充仓库外 `replay_baseline_partition.py` 可重现基线[9]、当前[5,4]；该补充回放不是第一次红测。
- 复用真实SQLite、费用账本、Artifact、Checkpoint、Adapter和生产聚合路径，只在Provider边界使用Mock。共享夹具增加数据集参数，并对同批多候选共享验证调用去重建档，旧五项路径保留。
- 两次实际Mock请求分别完整交付原五项和四项，字段投影与原判断一致；整组肯定后输出两条，各五/四份贡献，原始Evidence并集保留；两份审计及Checkpoint引用可分别回放。
- 根因、必要触发、必要修复任一否定/不确定时，只保留已确认组合并，另一组原项分立。跨组、子集、额外、重复句柄被原门禁拒绝；两组共享两次纠错，没有重置额度。
- 预算耗尽时保留已确认第一组与第二组原项，下一组零发送；测试为编排和实际发送账本注入同一个真实Ledger时钟。早期仅改编排时钟的装配导致预算探针失败，修正的是测试时间注入，没有改生产费用/期限逻辑。阶段失败日志保留。
- 第二组未知远端结果仍抛致命错误，保留费用预留及第一组已确认审计，不降级为可成功交付的预算退出。
- 异键桥接、同键真实桥、字段任一不同、单项零调用、乱序稳定、不同严重度/类别不分区、32项前置分块均覆盖。66项合成容量探针仅验证有界分块算法，不扩大生产全局候选额度。
- 历史036/037/038回复在原九项上下文仍被拒绝；没有把旧选五项回复套到新分组后追认历史成功。

## 新鲜验证

Python：`services/review-harness/.venv/Scripts/python.exe`，仓库根运行。

| 范围 | 命令概要 | 结果 |
|---|---|---|
| 新增预分区专项＋既有去重10次/600秒账本门禁 | `-m pytest services/review-harness/tests/integration/test_dedup_partition.py services/review-harness/tests/integration/test_fee_ledger.py::test_summary_has_its_own_ten_calls_and_ten_minutes -q --tb=short` | **24通过，135.59秒** |
| 原去重、多Pass、位置Schema | `-m pytest services/review-harness/tests/integration/test_dedup_fix.py services/review-harness/tests/integration/test_multipass_runtime.py services/review-harness/tests/unit/test_position_schemas.py -q --tb=short` | **70通过，327.84秒** |
| Schema元校验 | 对contracts/schemas下所有Schema调用Draft202012Validator.check_schema | **72份通过**；合同文件无差异 |
| 静态/格式/差异 | Ruff check全目录、Ruff format --check全目录、git diff --check | **通过；144个Python文件已格式化** |

共94项不同测试通过；开发阶段2项定向复跑和早期通过不重复累计。最初阶段 `partition-first.log`（18通过/1失败）、`partition-final.log`（22通过/1失败）均为上述单一预算测试时钟装配问题，最终 `partition-final-v2.log` 24通过。最终产品代码在这些验证期间未再修改。

未运行全量/干净环境、Java/UI/Legacy或真实模型；此前70以外历史矩阵不作为本轮新鲜通过。旧五项夹具的Git工作树无差异，当前CRLF与Git规范化LF原始字节不同，已明确记录，不声称跨检出原始字节相同。原真实Result SHA `63c6d95c48635b6b2d2e03eb169e529714f641edb0ec3e8ea75adeaceaa9c214` 及Pass原文哈希保持不变。

证据：`C:/Users/ROG/AppData/Local/Temp/code-weave-dedup-partition-offline-srlzgtu2`，包含阶段/最终日志、基线回放、fixture-integrity.json、Schema/静态结果、生产和交付文件摘要、最终源码副本及仓库外追加回执。仓库中此前已存在的真实验收文档差异、output和架构可视化不属于本切片改动。

## 限制与独立复审入口

预分区只消除平台已知不兼容的比较，不证明同键项语义相同。Mock不证明真实模型会返回合法整组决策；本轮没有新增真实成功证据。跨side、无重叠、跨块及同键混合缺陷的保守漏合并仍保留，不做子组聚类。通用DEDUP反馈未单列expected/missing/unexpected、Active pass语境残留不在本切片补修。历史失败、Result、费用、Checkpoint与旧审计不改写。

请设计窗口独立检查先分区后连通/分块、稳定预算优先级、分组审计、两组共享额度和失败保护。通过后再决定提交或另行授权真实验收；本交付不自动授权付费或进入下一批。
