# 批次 5：业务要求与实现事实混淆补修

本次只补充调查/验证的事实来源指令和验证候选的修正传递，不增加付费请求，不修改历史真实 Result，也不进入下一批。

## 原因与修复

用户输入可以定义“null 应返回零”的期望行为，但其中“方法是 public”的实现描述必须由源码核实。原调查指令没有明确这一区分；原验证协议只有 disposition 和 validationSummary，验证者即使指出描述错误，生成 Finding 时仍会原样复制调查候选。

1. 真实调查及验证请求的共享平台策略明确：用户输入定义期望行为，源码、Diff 等原始材料决定实现事实；核实用户对实现的描述，明确指出冲突。验证指令另要求检查候选的关键事实、必要假设、影响范围与最强反例。
2. 内部 CandidateValidation 增加可选 `correctedCandidate`，复用完整、严格的 CandidateFields。无修正时保持原协议行为；有修正时，模型须在同次独立验证中核实修正后的候选。所有文本字段可一并修正，不做 public 字符串替换，也不修改 Markdown 来掩盖原候选。
3. 修正不能变更 `passId`、`claimKind`、`evidenceIds`、`codeReferences`。验证批必须恰好覆盖原候选身份，不接受未知、重复或缺失候选。非法修正走现有脱敏诊断与全局最多两次纠错，未改变 Schema 严格性、来源门禁和调用预算。
4. 通过校验的修正用于构造新的有效 Finding，保留原 candidateId，再经原定量声明降级、必要假设、反例与 Finding 校验。原调查 State 候选和 Evidence 不被覆写，Result 2.0 外部结构保持不变。

## 离线回归

测试 seam 沿用既有生产 App、真实 Adapter 请求构造、HTTP MockTransport、认证 Result API。零外部模型请求。

- 冲突输入：用户明确称方法 public，源码保持默认权限；调查提交故意保留 public 错误，验证返回完整修正。最终 Finding 为 package-private，并限定在可访问范围内调用；有原文支持的 null 问题仍为 VERIFIED，报告从修正后的 Finding 生成。
- 核心反例：原文存在 null guard，拒绝 no-guard 候选；以及候选只声称其他包可直接调用，默认权限推翻该必要前提，不能生成 VERIFIED。
- 对抗边界：修正更换源码 hash、证据 ID、claimKind、候选 ID 或添加未知字段，最多三次验证响应后安全失败，无 Result。修正后出现定量声明或未解决假设时，原门禁仍降为 RISK。
- 红测记录：第一轮 fixture 对 provider-only 原材料块的解码错误导致 PROVIDER_RESPONSE_INVALID，不作为有效业务红测证据。修正 fixture 后，受控禁用“将候选修正用于 Finding”得到明确失败：期望 package-private 标题，实际仍为 Public method。恢复后冲突输入与核心反例通过。

新鲜完整测试结果在验收完成后追加，不复用旧轮次通过数。

## 限制与复审范围

这是事实核对协议与严格修正通道，平台没有新增 Java 解析器或确定性语义判定器。离线模型替身证明指令到达实际请求、修正到达有效 Finding、原门禁仍生效；不能证明真实模型必然识别全部错误事实。此次未付费验证新的模型行为，未重跑 Java、Legacy、前端或干净环境。独立验证仍使用同一供应商模型的独立请求，不是独立供应商裁决。

历史真实验收目录 `C:/Users/ROG/AppData/Local/Temp/code-weave-real-smoke-j5od_put` 与既有 Result 保留，原访问权限错误作为历史限制保留。本补修待设计窗口独立复审；未暂存、未提交、未推送。


## 2026-10-12T14:20:14+08:00 补修最终离线验收

- Python 全量新鲜结果：410 项通过，424.96 秒；basetemp 为 C:/Users/ROG/AppData/Local/Temp/code-weave-b5-fact-correction-20261012。
- 本补修与证据协议专项：35 项通过，127.97 秒。删除重复的验证身份检查后，最终代码上新增 10 项再次通过，59.72 秒。
- Ruff 全服务通过，format 117 文件通过，git diff --check 通过。
- 历史真实 Result 原 SHA sha256:25bd13262a1d16ba869a896a4b58727506c9c8b9e553814afb7e4476d3a4ea91 与 canonical bytes 只读核对一致，原 public 描述保留。外部问题日志仅追加并验证旧字节前缀；未改不可变产物。
- 本轮零付费请求。Java、Legacy、前端、干净环境及真实模型新指令行为未重复验收。协议和来源/身份/预算门禁通过离线验收，模型是否正确识别所有事实仍需独立评估。
- 待设计窗口独立复审；未暂存、未提交、未推送，不进入下一批。
