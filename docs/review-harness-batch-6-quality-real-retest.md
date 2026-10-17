# 批次6：Finding资格补修后单次受控真实复验

记录时间：2026-10-16T17:34:21+08:00。本轮授权：一个新六Pass Run，管理预算¥3／最多60次实际Provider尝试，先到即停，失败不自动重跑。真实CC未授权、未运行；不提交、不推送。

## 结论

**真实验收未通过，FAILED + RELEASED，无最终Result。** 29次请求、管理支出¥0.461080，所有Usage已知、29份预留均SETTLED，预留0／超支0。失败后没有继续付费、没有改产品或重跑。

局部真实观察：一次JSON语法错误获得具体位置及完整脱敏拒绝正文，下一请求修正成功；两个良性候选均被REJECTED、未变成Finding；已完成Pass存在源码/Evidence支持的null回归Finding。这些事实不能替代六Pass及最终报告验收。

## 版本、输入与预检

HEAD `30a0054f7ab3ec8ed865ce2ff0f18ccd25d7809a`，加已独立复审、保持未提交的R02/R03补修。两份生产文件SHA256见证据根 `implementation-fingerprint.json`；执行前后相同，完整已跟踪差异保存为 `workspace-diff.patch`，无关output/架构可视化未操作。本轮没有新的产品或测试源码修改。

证据根：`C:/Users/ROG/AppData/Local/Temp/code-weave-b6-quality-retest-428huzp2`。

- Run `47145d56-1f5c-4341-8198-4bb4207cf255`，Thread `6d5dd3ee-0eae-4c49-9b77-25ec9bafff32`，新独立Run/Checkpoint/账本。
- Session `3baf6bfb-21f6-4c37-94f4-2d25779396ab`，Snapshot `3ae808d8-a0bd-4794-b220-1f142d18d2f0`。
- 固定Manifest `sha256:0eb71f4e59d7ab1cc35dec7f3f4e1167ed09b65cbc3bc040d5e09b6fe2c753ed`，基线 `9cef7bdc6c10880036db73912003da23c22b160b`，工作区指纹 `sha256:4ffcbfb583aed755c60c5e224fd0ee41b6a3c368ed5c9028970497981b8526da`。
- Snapshot为此前原件的逐字节副本；不重采Diff、不续跑旧失败任务、不纳入oracle。用户模型输入沿用64固定原文，见 `real/request.json`。
- 官方价格 https://api-docs.deepseek.com/zh-cn/quick_start/pricing/ ，2026-10-16T09:15:16.776642Z重新抓取，原HTML SHA256 `5a7b1832592387340f2fc456399b34b89b05f3fa167c2e35909e2fa4afe021e3`。仍按高峰输入未命中¥2/百万、输出¥8/百万保守预留。Profile `official-flash-20261016-quality-retest`，到期2026-10-16T15:15:16.776642Z；原HTML、价格摘录、Profile均保存。
- 沿用用户已授权密钥，通过进程getpass输入；不保存凭据、认证头或隐藏思考正文。
- 生产认证Readiness、价格摘要和Capabilities均READY。最初离线Mock因临时execution/artifact根目录未创建而健康503，补齐验收环境目录后离线预检通过；当时零付费。随后使用分目录隔离的最终Mock预跑，12次模拟请求完成六Pass，仅为装配证据。
- 仓库外60次严格计数门禁复验：70并发、60到下游、10拒绝；未知尝试仍计次数，各purpose共用，发送前SQLite FULL提交/确认。HTTP retries=0，正式Plan的300/2000合同未改；¥3由正式FeeLedger预留/结算门禁执行。
- 新增仓库外RecordingTransport：计数门禁之后、真实发送之前可靠保存脱敏请求；回复保留可见正文、工具调用、finish_reason、Usage和原始响应摘要，剔除隐藏reasoning字段，不保存头。离线证明脱敏、回复原字节交回Adapter、隐藏内容不落盘。每回复捕获上限2MiB，超限即失败而不丢证据继续；本轮未触发。

入口是Python正式认证ASGI Run创建及Java已保存固定Snapshot，**不覆盖Java在线Review启动API**。只有一个真实POST Run；Mock、离线复制读回不算真实Run或付费请求。

## 六Pass事实与费用

UTC创建09:18:07.633062，终止09:20:51.589403。

| Pass | 状态 | 请求数 | 中间Finding |
|---|---|---:|---|
| correctness | COMPLETED | 5 | 1个VERIFIED null回归；良性c2被REJECTED |
| consistency | COMPLETED | 5 | 1个VERIFIED null回归 |
| security | COMPLETED | 6 | 1个VERIFIED null回归；材料自身注明非已证实安全漏洞 |
| performance | COMPLETED | 6 | 0；性能中性候选c5被REJECTED |
| architecture | INCOMPLETE | 7 | 验证失败，无该Pass有效Finding |
| testing | SKIPPED | 0 | 未执行 |

前三Pass重复问题尚未最终去重，不能称为三个独立Bug或已交付报告。security重复correctness结论也不证明安全维度质量；其原限制中明确没有认证/注入/序列化安全证据。

输入151756、输出19696、总171452 Token。29次调用均LOCAL_CLOSED、remoteOutcomeUnknown=false，29份费用记录SETTLED；2×151756 + 8×19696 = 461080微元，等于账本¥0.461080。reservedMicros=0、reservedCalls=0、overrunMicros=0。管理支出按保守价计算，**不是控制台账单**；剩余额度不是重跑许可。

## JSON目标：真实触发并修正

performance第21次调用（VALIDATION）正文JSON语法非法，finish_reason=stop；这不是按工具调用/调查自由文本误判JSON。第22次实际请求包括：

- `JSON_INVALID / SYNTAX_INVALID`，line=1、column=2916、零基position=2915；
- 恰好一个完整JSON对象、无尾部/围栏的明确规则；
- 第21次完整脱敏拒绝正文，逐字与生产redacted_submission结果一致；
- 保留候选和原始材料，输出模式仍json_object。

第22次回复finish_reason=stop，JSON合法，验证将c5 REJECTED，performance正常完成；在现有额度内成功，不增加重试。证据为 `real/traces/021.response.json`、`022.request.json`、`022.response.json`，离线断言保存于 `offline-analysis/json-correction-observation.json`。这只证明一次语法错误的真实纠错成功，不证明上轮Extra data尾部错误或所有JSON错误已被真实覆盖。

## 缺陷资格目标：局部观察通过，仍有模型局限

correctness第4次调查仍提出良性局部变量候选c2；第5次独立验证将其REJECTED，而将有具体null触发和源码支持的c1 VERIFIED。performance第20次也提出性能中性候选c5，第22次将其REJECTED。中间Finding均未把requiredLength安全重构当作缺陷；调查仍产生冗余良性候选，不声称模型已稳定学会只提交合格问题。

null回归Finding依据 unchanged Javadoc要求null返回0、Diff删除null分支、最终源码直接text.length()；原Evidence/引用/哈希由平台校验。前三Pass保留其真实验证摘要和必要限制，不用评估oracle给模型提示。该Run没有最终Result，不能把中间Finding当作已交付报告。

## 实际阻塞：architecture逐字引用失败

第26次调查提交遗漏candidates，Schema拒绝；第27次补回候选但codeSelections只给startLine未给endLine，再被Schema拒绝；第28次提交有效，已花完该Pass共享的两次纠错额度。

第29次（该Pass首次独立验证）提交VERIFIED，但factChecks[1].candidateText用字面 `...` 连接候选中的不连续片段，不是候选原文的连续逐字子串。sourceQuote本身可解析为该候选授权Diff材料，但candidateText真实性门禁仍应拒绝。生产 `_validate_corrections` 离线重放精确复现：

`PROVIDER_VALIDATION_INVALID / FACT_QUOTE_INVALID / validations[0].factChecks[1]`。

此时Checkpoint revision121、correctionCount=2，按既有上限直接终止，无第三次纠错。没有截尾、补行号、自动改引用或额度；不把反复付费当作定位方式。本次不自行判定为平台Bug或新增Schema设计，交设计窗口复核请求协议可用性与停止事实。

最终failure diagnosticId `4c66e70a-e299-4be8-b567-b26c92cd9f01`。精确重放脚本 `C:/Users/ROG/AppData/Local/Temp/code-weave-b6-quality-retest-428huzp2/offline_diagnose.py`，仅parse_submission→resolve_validation→_validate_corrections，不调用Adapter、不写原数据库。输出 `offline-analysis/final-failure-replay.json`。

## 证据交付与未验项

- `real/traces/`：29份脱敏请求＋29份回复投影。所有requestDigest均匹配正式ModelCall记录；本合成输入redactionState均NONE。Provider Usage额外的prompt_tokens_details保留在回复中，规范化的五个Usage字段逐项等于账本，未将原始额外字段误当差异。
- `real/runs.sqlite`、`checkpoints.sqlite`、events/status/request/price/capabilities、调用和账本摘要保持原样。`evidence-file-digests.json`供后续校验。
- `offline-analysis/call-reconciliation.json`、`settlement-check.json`：请求与Usage对账、29笔费用结算。
- 使用独立数据库与Artifact副本启动只读用途的认证读取App，Provider传输被Mock硬阻断：10条Evidence材料、5份诊断Artifact可认证读取；无认证401，Result读取409。零Provider尝试；保存到 `readback/`。这不是新模型Run，不改原件。
- 三项新鲜离线检查分别通过：发送门禁并发/未知计数；记录器原字节交回及脱敏；原失败生产校验路径重放。它们是验收装配/诊断，不冒称生产全量测试。
- 本轮无Result，因此最终Result3、最终去重/汇总、Java导入/UI/Handoff未验，未运行ImportResult。辅助尝试Maven离线compile/dependency:build-classpath因alimaven离线缓存来源不匹配未通过，未改Maven配置；不把旧Java/UI结果冒称本轮通过。
- 本轮未重跑全量、干净3.12、Java/Legacy/前端矩阵；前次181项补修回归属于历史证据。未运行真实CC，未提交推送。

下一步只把本记录及脱敏回放证据交设计窗口，决定是否需要协议补修；不自动改实现，不自动再创建付费Run。
