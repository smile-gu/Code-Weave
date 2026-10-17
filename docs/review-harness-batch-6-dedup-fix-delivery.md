# 批次 6 最小语义去重补修交付

日期：2026-10-16。基线 `d5f75f0239010ff911c373482cd63a25d5df3b52`，工作区未提交。
依据：[冻结稿](review-harness-batch-6-dedup-fix-freeze.md)。本轮仅离线开发，未读密钥、未发送付费请求、未运行真实 CC、未提交推送。

## 实现与保护

- `runtime/dedup.py`：以同路径、side、哈希、行区间重叠构建连通分量，移除 rule 文本相等要求。固定 PASS_ORDER/candidateId 排序；超过32项按固定块再次求分量，跨块遗漏明确记录。来源连通仅用于召回，不代表同缺陷。
- `merge_group` 必须接收精确本组的已校验 DedupDecision 和平台候选映射；三项均 true，句柄无遗漏/重复/额外项，原始 PassFinding 有效、来源连通、claimKind/disposition一致才能合并。移除 trigger/suggestion 全文相等门槛。不同等级仍取固定主项，模型不改级、不改文。
- `runtime/multipass.py`：请求交付所有原始判断，明确整组根因、必要触发与必要修复的语义比较；不确定、混合不同缺陷整组分立，不做子组。继续使用现有 `_json` 两次共享纠错及10次/600秒、Run费用/调用/时间预算。
- `runtime/dedup_audit.py`：核实当前Run/Snapshot、Pass结果账本、候选、验证调用及原始Evidence/代码引用身份；使用既有 ArtifactService 发布 SYSTEM/TOOL_OUTPUT `recordType=dedup_decision, recordVersion=1.0`。完整保存原成员及canonical摘要、分组/重叠边、决策和理由、最终动作及主项/合并摘要。Artifact FULL且哈希一致，State.artifactRefs经Checkpoint精确确认后才采用合并。
- 合并保留所有Evidence、代码引用、每个原始contribution；16/16/32及原字节限制不变。合并容量、完整审计容量/引用容量不足时保留原项并说明限制。写入/完整性/身份/取消/未知远端结果不降级成可忽略预算错误。
- 不改 State5/6、POSITION_V1、DedupDecision字段/Schema、Result3、Java/UI、Evidence提交、事实验证、收费账本与Pass执行协议。

## 离线验收方法

1. 先写真实五项原文召回红测，旧实现返回0组；移除文本门槛后变绿。再验证未经决策不能合并、肯定整组决策可合并不同措辞。
2. `tests/fixtures/position-real-dedup-findings.json` 的五条Finding与真实 Result `sha256:7163b2e9c9f7e3f8e9f30df295b6f5b416db11e7c3597b5955691c36a9c268ae` 内容逐项一致，未统一 rule/trigger/suggestion，未改历史产物；源码与Diff同样复制原固定输入。
3. 正式聚合测试使用真实SQLite、Artifact、Checkpoint、费用账本、Adapter和生产 `_deduplicate`，仅Provider边界使用 httpx.MockTransport。测试装配为新身份建立原始判断/验证调用事实，模型语义决定是脚本输入；不冒称重新调查或真实模型语义通过。
4. 五项整组确认后得到一项、五份贡献、全部来源、固定主项；审计原文及SHA可分页读回，Checkpoint引用可精确确认。原六Pass生产执行/Result3严格落库读取另有原整合回归覆盖。
5. 覆盖不同根因/触发/修复、重叠链、无重叠/跨哈希/side、混合判断、等级、句柄/JSON/缺字段纠错耗尽、Evidence/引用/32项容量、审计/引用超限、存储/哈希/Checkpoint失确认、取消、未知远端、Run期限、回执丢失后精确确认、重启和篡改保护。
6. 去重生产请求显式传入整组消息，`Compactor.fit` 放不下时保留预算错误，不在该路径自动摘要。测试开发中强制调用“去重阶段摘要”的探针被现有费用阶段门禁拒绝；没有为此扩合同/借额度。最终分别验证显式去重请求引用保留和受支持普通Pass压缩对 State.artifactRefs 的保护。

## 新鲜结果

- 最终去重/多Pass整合：**66通过，243.64秒**，`final-aggregation-v2.log`（37项新增去重专项及29项多Pass整合）。测试断言另强化了Evidence/代码引用容量检查，对应4项复跑通过（0.26秒），不重复计入66。
- 新增断言覆盖空白理由、未知调用继续保留预留、原验证调用/来源/重复身份被篡改时发送前阻断；原费用专项继续覆盖去重10次与600秒的实际账本门禁。

- 受影响协议回归：211通过，351.66秒；位置单元/Schema/生产验证、旧局部句柄、事实修正、压缩、费用账本、Result3合同。日志 `affected-protocol.log`。
- 首轮去重与多Pass：55通过，194.76秒，属于整合阶段证据，不冒称最终工作区全量。
- 72份合同Schema元校验通过；生产Schema一致性由对应专项断言。Ruff全目录检查通过、143个Python文件格式检查通过、`git diff --check`通过（`static-checks.json`）。
- 本轮未跑Python全量/干净环境、Java/UI/Legacy；相应历史结果不冒称本轮结果。

证据根：`C:/Users/ROG/AppData/Local/Temp/code-weave-dedup-offline-cyb5tzzk`。含各阶段日志、命令与时间、生产摘要和差异、Schema及追加回执；初期测试装配错误和越过生产调用边界的失败探针保留区分，不作最终产品通过证据。

## 剩余限制与复审入口

- Mock只证明协议、确定性处理和失败保护，不证明模型会正确判断同根性。暂不付费，交设计窗口独立复审后另行授权。
- 语义理由只有非空/有界与身份校验；平台不确定性证明自然语言充分性，不做关键词裁决。
- 混合组整组分立；跨side、无重叠、跨块可能漏合并；不新增AST、Embedding或子组聚类。
- 主项等级按固定顺序保留，其他成员不同等级/措辞从不可变审计回放；外部Result不新增字段。
- Artifact与Checkpoint仍非原子事务；孤立Artifact并不证明合并已采用，必须同时确认Checkpoint引用。失确认停止，不自动重试或恢复执行。
- 旧真实Result与其重复项、模型摘要行号偏差均未改写。本轮不能据离线结果批准整批，也不进入下一批。
