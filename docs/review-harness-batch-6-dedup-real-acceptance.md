# 批次 6 最小语义去重：单次受控真实验收

验收基线：`6ef9a018a06f50b0acb1ae437cf3a8833edc41ec`。真实执行时间：2026-10-16 20:10:20–20:15:21 +08:00。离线核对记录：2026-10-16T20:21:40.539002+08:00。

## 结论分层

| 维度 | 结论 |
|---|---|
| 执行闭环 | 通过：唯一新Run，六Pass COMPLETED，Result3.0，SUCCEEDED / RELEASED |
| null回归／良性重构 | 检出null回归；requiredLength局部变量重构候选c2明确REJECTED，未进入Finding |
| 来源召回／完整材料交付 | 通过：9条原始Finding形成1个9项重叠组，36条边；三次实际请求均完整交付九项，字段与原始判断逐项一致 |
| 协议与安全门禁 | 正确拒绝非法字段和子集回复，两次纠错耗尽后原样保留九项，没有强并、截断或改写判断 |
| 模型整组语义决策 | **未通过**：模型始终选择5条VERIFIED，排除4条RISK，未返回合法的整组决策 |
| 已确认审计后合并 | **未验**：没有接受的去重决策，因此没有dedup_decision审计Artifact或合并采用；本轮不能证明该真实成功路径 |
| 整批提交条件 | 本轮不予通过，交设计窗口复核；不将SUCCEEDED等同报告质量通过 |

本次是“语义去重已触发，但合法整组判断未完成”，不是“未产生重复项所以未触发”。九项属于混合claimKind/disposition组，冻结稿本就接受整组保守分立；不能为了获得一条而放宽门禁、挑子组合并或把分立本身直接判为平台缺陷。

## 授权与固定输入

- 新Run：`187831ef-ae30-453c-9a01-81b8cd86a60e`，独立SQLite/Checkpoint/Artifact/执行目录；数据库确认只有一个Run。
- Snapshot：`3ae808d8-a0bd-4794-b220-1f142d18d2f0`；Manifest：`sha256:0eb71f4e59d7ab1cc35dec7f3f4e1167ed09b65cbc3bc040d5e09b6fe2c753ed`。
- 沿用上一轮固定Snapshot，3个文件字节SHA逐项相同，未加oracle或缺陷提示。用户输入保持原文：

> 请审查当前累计变更，依据仓库中的接口契约与原始源码判断行为变化；区分已证实问题、风险和建议，并如实说明检查范围。

- 执行前后79个产品文件摘要一致；services/review-harness及contracts均与HEAD无差异。位置协议和生产实现未修改。
- 本地未配置环境密钥，用户再次明确沿用此前会话密钥；仅通过getpass隐藏输入供进程使用，未落盘密钥、未记录认证头、未保存隐藏reasoning_content。
- 管理上限¥3，所有purpose共用发送前SQLite FULL确认的60次硬门禁；HTTP transport retries=0，未知尝试不退次数。离线70并发预检中60放行/10阻断，未知发送保留计数，五类目的共用。脱敏记录预检通过。
- 仅一个真实Run；没有额外模拟Run、没有付费重跑，没有真实CC。内部ASGI使用Python正式创建/查询入口及既存Java Snapshot，本轮未覆盖Java在线Review启动、UI或Handoff。

## 费用、Usage与资源

官方价格：https://api-docs.deepseek.com/zh-cn/quick_start/pricing/ 。本轮保存HTML，SHA `5a7b1832592387340f2fc456399b34b89b05f3fa167c2e35909e2fa4afe021e3`。Profile `official-flash-dedup-02f8d0172fb0` 于12:09:26Z核验，18:09:26Z到期，整个执行均在有效期内。官方deepseek-flash对应DeepSeek-V4.1-Flash。

按最高时段且输入不采用缓存折扣保守预留：输入¥2/百万、输出¥8/百万。**¥0.890296是管理保守计费，不是控制台实际账单**，本轮未取得实际账单金额，不把历史累计金额混入。

| 项目 | 本轮事实 |
|---|---|
| 总发送／可见回复 | 38／38，均有落盘记录，小于60 |
| purpose | INVESTIGATION 26、VALIDATION 9、DEDUP 3；纠错计入对应purpose，不额外豁免 |
| Token | prompt 311248、completion 33475、total 344723 |
| 调用终态 | 38份LOCAL_CLOSED，Usage齐全，remoteOutcomeUnknown=0 |
| 账本 | 38份SETTLED；spentMicros=890296，reserved=0，overrun=0 |
| Run资源 | RELEASED；execution_workspaces=REMOVED，.staging为空，无执行副本残留 |
| Result | 3.0，ID `a7cc773f-f46e-439e-96c9-6d616bf482e5`，SHA `63c6d95c48635b6b2d2e03eb169e529714f641edb0ec3e8ea75adeaceaa9c214` |
| 完整性读回 | 51份Artifact SHA/字节数一致；171份Checkpoint严格解析及stateDigest一致，全部State6 |
| 认证副本读回 | Result3严格解析；未认证401；两次读取原字节与ETag一致；17份Evidence材料可认证读取；硬拒绝Provider，零新增调用 |

## 去重实际经过

原始Pass判断：correctness 2项、consistency 1项、security 2项、performance 0项、architecture 2项、testing 2项，共9项。最终是5项STATIC_CORRECTNESS/VERIFIED的源码契约回归，以及4项ENVIRONMENT_DEPENDENT/RISK的外部调用影响；全部围绕nullableLength的null容忍行为变更，后者保留环境假设，未作为已证实影响冒充VERIFIED。

候选c2为requiredLength等价重构，独立验证REJECTED；另有c5风险候选的counterexampleHolds=true，没有形成Finding。最终九项均与原始Pass判断的unmerged投影完全一致，没有人工作文修正历史产物。

固定召回顺序：`c1,c3,c4,c7,c6,c9,c8,c11,c10`（按Pass/candidateId，而不是句柄自然数排序）。模型每次返回 `c1,c4,c6,c8,c10`，遗漏 `c3,c7,c9,c11`。

| 请求 | 实际回复与门禁 |
|---|---|
| 036 DEDUP | JSON语法合法，5项子集且多出candidateHandles_note；生产Schema按extra_forbidden拒绝 |
| 037 第一次纠错 | 去掉额外字段，仍只提交5项；DEDUP_IDENTITY_MISMATCH拒绝 |
| 038 第二次纠错 | 请求含上一轮具体DEDUP_IDENTITY_MISMATCH反馈，仍重复5项子集；额度耗尽，安全保留原项 |

三份回复finish_reason均为stop，不是空正文或length截断；模型rationale明确写出排除RISK成员。它确实针对自行选择的五条解释了根因、null触发与恢复guard的修复方向，但这不等于对平台交付的整个九项组作出合法决策。不能采纳其“看起来合理”的子组判断。

未出现接受的整组决策，故dedup_decision Artifact数量为0；有三份SYSTEM拒绝诊断Artifact（SCHEMA_INVALID一次、DEDUP_IDENTITY_MISMATCH两次），原始回复与反馈、拒绝正文均在trace/Checkpoint保留。没有发生审计丢失或存储失败的证据；正确区分“尚未进入审计”与“审计持久化失败”。Result明确包含 `Possible duplicates retained: bounded structured comparison was rejected.`。

## 离线定位与尚未证明的原因

采用diagnosing-bugs流程的捕获回放方法，仅作仓库外只读定位，不进入补修阶段。

1. 支持“模型选择子集”：036/037/038完整材料相同，回复均主动排除四条RISK；生产parse_submission与merge_group回放分别稳定拒绝Schema/句柄不匹配。
2. 排除“材料漏交／映射错误”：三个实际请求的untrustedDedupGroup均为九项，每项内容与原始PassFinding全量投影一致，句柄来源与Checkpoint一致。
3. 反馈和提示仍有可供设计复核之处：038实际含DEDUP_IDENTITY_MISMATCH，但未单列expected/missing/unexpected组句柄；allowedHandles是Run级11项而非本组九项；通用指令仍带Active pass is testing等调查语境。**这些是可见事实，不足以证明它们导致了模型子集选择，本轮不改提示词。**
4. 单变量离线反事实仅补齐037的句柄、保留其余字段后，生产本地门禁仍返回Incompatible judgment kinds，因为VERIFIED/STATIC与RISK/ENVIRONMENT不可合并。该探针没有修改原回复或产物，不是验收成功数据，也说明“补齐句柄就一定得到一条”不成立。冻结稿不做子组聚类，本轮不改变这个范围。

实际完成了候选比较尝试，但未得到合法的整组否定或肯定决策。安全保留分立已验证；真实模型有效整组决策、完整去重审计确认后合并仍未验。不得据此宣称最小语义去重真实质量通过，也不得将混合组不能合并直接定性为平台Bug。

## 回放材料与命令

证据根：`C:/Users/ROG/AppData/Local/Temp/code-weave-b6-dedup-real-pezip0nv`。

- `real/traces/001..038.request/response.json`：脱敏请求/可见回复、finish_reason、工具调用和Usage；无认证头/隐藏推理。
- `original-pass-outcomes.json`、`persistent-pass-facts.json`、`candidate-validation-summary.json`：原始Pass与候选判定。
- `recalled-groups.json`、`dedup-provider-exchanges.json`、`dedup-rejection-diagnostics.json`、`dedup-failure-replay.json`：召回、模型决策及拒绝链路。
- `real/result.json`、`audit-summary.json`、`guard-summary.json`、`real/calls-summary.json`、`real/fees-summary.json`：结果、审计、资源及费用。
- `readback/`：只在原库/Artifact副本上进行认证读取，Provider硬拒绝。
- 原真实数据库/Artifact/Checkpoint、旧Run与费用均保留，没有恢复旧Run。

从仓库根使用 `services/review-harness/.venv/Scripts/python.exe`：

```text
<证据根>/audit_run.py                 # 离线一致性汇总，通过
<证据根>/replay_dedup_failure.py      # 预期exit1：三份真实回复均无法通过完整九项组；这是失败证据，不是产品回归通过证据
<证据根>/probe_mixed_group.py         # 离线单变量反事实，混合判断仍保守分立；不改原件
```

本轮未重跑产品测试矩阵、Java/UI、干净环境或真实CC；没有进行实现补修，不将历史离线计数冒称本轮结果。仅新增本记录及向主验收记录、仓库外日志追加，保持未提交、未推送。交设计窗口独立复核后再决定后续动作，**本授权已经用完，不自动新增付费Run。**
