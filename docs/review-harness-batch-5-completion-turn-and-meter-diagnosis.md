# 请求回合边界与真实 Token 诊断

日期：2026-10-12。用户已撤销累计请求次数限制，并将累计费用上界提高到 8 元。仍保留冻结的单 Run 16 次模型调用、硬上下文/费用/资源门禁。未提交、未推送。

## 计量差异的核对与修复

最小合成输入真实 Run `e2b88782-3380-4fa2-9aa4-104fdf5a3dc8`，目录 `code-weave-real-smoke-9nugtvte`，7 次请求后 FAILED + RELEASED。前 6 次输入计量完全相等，第 7 次本地 6758、Provider 6760，触发 PROVIDER_TOKEN_METER_DRIFT。所有 Usage 完整；本轮高峰缓存未命中上界 0.086510 元。原漂移记录未删除或清零。

离线重建请求的 SHA-256 与原发送意图一致，重复本地计量仍为 6758。Checkpoint 按 revision 选择，不按随机 ID 排序；已删除的执行副本不重建、不重放 Run。离线装配使用经过摘要核验的原 Snapshot 和持久 Evidence，在内存中跳过终态生命周期/已移除副本检查，仅重建请求。

请求中有连续两条 assistant：调查阶段的就绪回复及下一次结构提交的拒绝回复。原结构提交请求在 wire 中补了 user 请求回合，但保存回复后的历史缺少该回合。参考编码器对连续 assistant 的转换与服务端存在差异。按消息独立编码并不能修复：整串/分段均为 6758。

修复复用已有 completion cue，在连续 assistant 之间恢复单独请求回合，保留原回复、字段、工具 ID 和反馈。新增内容进入最终 wire、计量和 Manifest；不增加 Token 容差、不更换编码器、不扣减历史、不使用 beta prefix。原有尾部 completion cue 继续保留。

正式模型双探针仅改变这个回合边界：

| 请求 | 本地 | Provider | 差异 |
|---|---:|---:|---:|
| 原始请求，摘要与原失败意图一致 | 6758 | 6760 | +2 |
| 显式恢复请求回合 | 6772 | 6772 | 0 |

目录 `code-weave-meter-role-probe-uyix0_hb`，2 次正式请求，Usage 完整，费用上界 0.047528 元。诊断意图、摘要、Usage 与预留在发送前后保存；未保存凭据、原请求或回复正文。该探针不是 Review Run，未将其响应发布为 Evidence/Candidate/Result，也未解除原数据库的漂移保护。

这证明本次具体请求形态问题已修复，不证明参考计量具备全域精确性。用户提出有界容错有合理性；后续需以测试矩阵确定误差范围，计入调用前预留，并保留实际硬上限/缺失或损坏 Usage 的阻断。当前没有擅自实施容错政策。

## 费用保护与最小输入

手测增加 minimal 模式，保留同一个 null 行为要求和有缺陷的 Java 方法，只去掉用于测试 Artifact/分页的 200 行填充。不把最小输入成功冒称大输出全路径成功。

支持显式累计预算和此前费用上界。每次调用真实 Adapter 前预留最坏 0.088064 元；有效 Usage 返回后按高峰未命中价格结算上界，释放未用预留；费用/远端结果未知保留预留并阻断后续调用。仅在验收脚本包装真实 Adapter 调用，不改变请求、响应、模型输出、执行器或 Run 终态裁决。当前命令显式 --budget-cny 8，默认值仍 1，便于旧手测保持原约束。

## 修复后的真实复验与当前阻塞

Run `2fdb7990-42a2-4b7e-a1ce-97954316d943`，目录 `code-weave-real-smoke-8hswd8te`。7 次正式请求；前 6 次全部计量相等。第 7 次 HTTP 200、527 字节，在 choices[0].message 的 value_error 被拒绝，失败码 PROVIDER_RESPONSE_INVALID。依据该节点的 meaningful 校验可定位为空/空白 assistant，原响应未保存，不能区分 null、空字符串或空白字符串。

此处与 Token 漂移不同。合法源码已读完，覆盖 FULL；已持久保存 2 条 Evidence，但候选池 0、VALIDATION 0、Result 0。此前一次受控拒绝是 checkedScope 的 SCOPE_INVALID；未关闭范围门禁。最终 FAILED + RELEASED，执行目录和该 Run staging 已移除。

最后一笔没有有效 Usage，unknownUsageOrRemoteOutcome=true。费用账本保留：已知累计高峰上界 0.387256 元、未知预留 0.088064 元、保守总上界 0.475320 元。上述是上界，不是实际账单。累计本地发送尝试 45 次（包含 2 个 Token 探针）；次数限额已由用户撤销。

已向用户询问刷新后的控制台累计金额。冻结稿第 10 节“费用未知停止”仍适用；等待确认前不继续付费。没有自动重试、未伪造空回复或成功结果，完整 Evidence → 独立验证 → Result 尚未通过。

## 离线验证

- 新增回合边界回归先红后绿；Adapter/费用保护专项 35 通过。
- Python 全量 399 通过，332.31 秒，独立临时根 code-weave-b5-completion-turn-final-20261012。
- 全量启动后补充了显式 8 元预算配置；该费用专项最终 4 通过，含此前成本超过 1 元时的 8 元授权路径。它是补充验证，不冒称 400 项全量。
- Ruff/check format 116 文件及差异检查通过。Java/前端/Legacy/干净环境本轮未重跑。

保留真实 Run、Checkpoint、Artifact、ModelCall、费用与诊断原始事实。临时诊断脚本只有隐藏输入逻辑，没有内嵌密钥；凭据形态扫描 0 文件命中。


## 2026-10-12 真实最小闭环验收补充

- 费用确认：用户提供控制台 0.20 元 / 24 请求 / 116,027 Tokens；此前 0.09 元保守合并。保留更高历史预留上界 0.475320 元；累计授权 8 元，不限制累计发送次数，单 Run 16 次门禁保持。
- 协议补充：checkedScope 必须逐字复制 scope.targetPaths 完整文件路径，不得使用方法名、目录或描述标签；没有放宽校验或自动改写模型提交。
- 新鲜正式生产请求纠错回归：先红 12 项，补充说明后 12 项通过；本次修改两文件 Ruff / format 通过，git diff --check 通过。本轮未重复全量、Java、Legacy、前端或干净环境验收。
- 真实证据目录：C:/Users/ROG/AppData/Local/Temp/code-weave-real-smoke-j5od_put。
- Run c07a7fdb-078d-415a-84d9-c185d224c005：SUCCEEDED + RELEASED；Result 2.0，correctness COMPLETED / FULL，5 Evidence，1 VERIFIED Finding，无未验证候选。8 次官方 DeepSeek 请求包含 1 次独立 VALIDATION，全部有效 Usage，所有观察到的输入估计与实际相等；输入 32,339 / 输出 4,814 Tokens。
- 只读离线核对：Result API 保存文档与数据库 canonical bytes / sha256 一致；Evidence 数据库记录与 Result 一致，认证材料副本引用、原 Snapshot 完整 hash 与物理行正文一致；Finding 引用真实 VALIDATION 调用；Run 执行目录及 staging/run 已删除。核对摘要保存在 acceptance-summary.json。
- 本轮峰值未命中价格成本上界 0.103190 元；保守累计上界 0.578510 元，已释放本轮请求预留，无未知 Usage。此值是平台成本上界，不冒称控制台实际扣费。
- 限制：只证明最小公开合成材料的 Python 真实闭环。模型把 Java 默认访问权限的方法描述为 public，属于报告措辞事实偏差；null 解引用回归由源码、Diff、用户要求支持，仍成立。独立验证指独立请求和原始材料验证，使用同一模型，并非独立供应商。大输出、压缩及 Java/UI 不由本次证明。参考 tokenizer 主站身份校验限制保留，不能宣称所有请求绝对精确。
- 本轮真实闭环可交复审；不宣称整批提交条件达成。保持未暂存、未提交、未推送，不进入下一批。
