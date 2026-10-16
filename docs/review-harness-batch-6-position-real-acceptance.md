# 批次 6 POSITION_V1 单次真实验收

验收时间：2026-10-16 18:59:14–19:02:42 +08:00；核对记录时间：2026-10-16T19:05:53.257662+08:00。

## 结论

本次 Python 正式入口的 POSITION_V1 真实六 Pass 闭环完成，位置协议目标通过。报告仍存在同根问题重复展示的质量限制，不能据此批准整批提交。未修改产品实现、未运行真实 CC、未重跑、未提交、未推送。

## 固定条件与可回放证据

- HEAD `30a0054f7ab3ec8ed865ce2ff0f18ccd25d7809a`，叠加未提交的 JSON/资格及位置协议补修；`production-digests.json` 与离线交付摘要逐项一致，`workspace.diff` 保存运行时差异。不是纯提交基线测试。
- Run `0c84091a-8047-4f60-9c4e-500e7e26aa04`；Snapshot `3ae808d8-a0bd-4794-b220-1f142d18d2f0`，Manifest `sha256:0eb71f4e59d7ab1cc35dec7f3f4e1167ed09b65cbc3bc040d5e09b6fe2c753ed`。
- 原固定 Snapshot 复制后逐文件 SHA 相同，未添加 oracle、未调整用户输入。
- 证据根：`C:/Users/ROG/AppData/Local/Temp/code-weave-b6-position-real-qmb0wiq8`。`run_review.py`、`attempt_gate.py`、`recording_transport.py`、`identity.json`、价格原件及生产摘要保留。真实产物在 `real/`，离线认证读回在 `readback/`，汇总在 `audit-summary.json`。
- 官方价格 URL：https://api-docs.deepseek.com/zh-cn/quick_start/pricing/ 。HTML SHA `5a7b1832592387340f2fc456399b34b89b05f3fa167c2e35909e2fa4afe021e3`；按高峰输入未命中 ¥2/百万、输出 ¥8/百万保守计费，6小时有效 Profile 绑定本次授权。
- 一个新 Run、¥3、所有 purpose 共用最多60次发送；SQLite FULL 持久计数先确认再发送；HTTP retries=0。离线 Gate 70并发中60放行/10阻断，未知发送保留计数；Recorder 脱敏、不保存认证头/隐藏 reasoning，原响应交回 Adapter。
- Readiness/价格/能力均 READY。仅使用 Python 正式 API 和已保存 Java Snapshot，未覆盖 Java 在线启动链路。

## 本轮新鲜证据

| 项目 | 结果 |
|---|---|
| 离线装配预检 | 模拟六 Pass 12次，SUCCEEDED；只证明装配 |
| 单次真实执行 | 33次，SUCCEEDED / RELEASED，无自动重跑 |
| 六 Pass | correctness、consistency、security、performance、architecture、testing 全 COMPLETED；SELECTED_COMPLETE |
| Result | 3.0，SHA `7163b2e9c9f7e3f8e9f30df295b6f5b416db11e7c3597b5955691c36a9c268ae` |
| 新协议与持久化 | 151份 JSON Checkpoint 均 State6；5份接受验证记录、24条位置绑定可按认证 Evidence 原文重取一致；全部验证 Artifact 被 Checkpoint 引用 |
| 完整性 | 41份 Artifact 正文字节 SHA 与数据库记录相符 |
| 认证/幂等读回 | 在数据库/Artifact 副本上，未认证401；Result 两次读取与原始字节及ETag一致；15份Evidence材料全部200；Provider硬拒绝，零调用 |
| 真缺陷 | nullableLength(null) 从0变NPE，源码及Diff支持，最终 VERIFIED |
| 良性反例 | correctness c2（requiredLength局部变量重构）REJECTED；最终无该良性重构缺陷 |
| 真实纠错 | 006验证JSON在line1/column2990/position2989非法；007请求携带具体SYNTAX_INVALID诊断及完整拒绝提交，回复变合法但混入codeSelections；008具体extra_forbidden反馈后修正成功。共享两次纠错未增加 |
| 其他非JSON回复 | 004/011/016/021/026/031调查返回普通报告文字，现有续接要求其按平台格式返回，随后成功；不应与006语法错误混淆 |
| Usage | 输入192289、输出22878、总215167；33个 LOCAL_CLOSED、Usage已知、remoteOutcomeUnknown=false |
| 账本 | 33份 SETTLED；管理保守计费 ¥0.567602；reserved=0、overrun=0、unknown=0。此金额不是控制台实际账单 |

## 报告质量限制及未验范围

1. 最终5条Finding均围绕同一 nullableLength null 回归（correctness/consistency/security/architecture/testing），performance无Finding。去重模型调用为0。离线读代码确认 `runtime/dedup.py:recall_groups` 要求重叠来源且归一化后的 rule 完全相同；这五条措辞不同，未进入去重组。未作任何运行后人工合并，待设计窗口评估重复展示和Pass归类质量。
2. 部分Evidence摘要把final Javadoc写为第7行，实际第8行；平台位置绑定及取文正确，但不能把位置合法误认为模型摘要语义/行号描述完全正确。原件保留，未手工修正。
3. 多Pass同模型验证并非独立模型裁决；安全重构不误报及null检出仅证明这个固定合成样例。不能泛化为真实项目质量保证。
4. 未触发真正上下文压缩，未验256K高容量、超窗恢复（仍DISABLED）、POSIX、真实Coding。
5. 本轮未运行Java导入/UI/Handoff、全量/干净环境/Legacy矩阵，历史证据不作为本轮通过。上次离线694全量及后续专项也不冒称当前709项全量。
6. 当前模型官方别名为deepseek-flash，官方页面对应DeepSeek-V4.1-Flash；有限样例不证明长期供应商行为稳定。

## 操作与下一步

本轮只新增验收文档与仓库外证据，产品摘要未漂移。交设计窗口独立复核位置协议真实完成、纠错链路、重复Finding与摘要偏差。未经复核不宣称整批通过，不追加付费调用。
