# Contract Schemas

该目录保存 Code Weave 跨进程或跨服务的版本化机器契约。Schema 描述计划接口不代表对应服务已经实现；实现状态以项目架构文档和 ADR 为准。

## Review Harness v1

| Schema | 用途 |
|---|---|
| `review-harness-common-v1.schema.json` | UUID、SHA-256、时间、状态、Failure 和 Result Descriptor |
| `review-harness-create-run-v1.schema.json` | `POST /internal/v1/review-runs` 请求 |
| `review-harness-run-status-v1.schema.json` | 创建、查询和取消接口复用的 Run 状态响应 |
| `review-harness-event-v1.schema.json` | Python → Java 的持久 Review Run 事件信封 |
| `review-harness-health-v1.schema.json` | Readiness 响应 |
| `review-harness-result-v1.schema.json` | 不可变 Review Result Envelope |
| `review-harness-problem-v1.schema.json` | RFC 9457 Problem Details 扩展 |
| `review-snapshot-manifest-v1.schema.json` | 批次 2 不可变 Snapshot Manifest，批次 2 已实现 |

字段语义、HTTP 状态、事务和恢复规则以 ADR-0009 为准；Snapshot 身份以 ADR-0014 为准；Result 内部 Evidence/Finding 演进以 ADR-0015 为准。

JSON Schema 无法表达或不适合表达的规则由应用层和契约测试负责，包括：

- 请求和字符串的 UTF-8 字节上限；
- `input.message` 至少包含一个非空白字符；
- Result `byteSize` 等于 Markdown 的 UTF-8 字节数；
- 时间先后关系；
- Result/Run/Snapshot 身份完全一致；
- 事件序号连续性和数据库事务原子性。
- Snapshot 路径无空段/尾斜杠、可移植设备名约束、大小写/NFC 冲突、目录前缀冲突、列表排序及范围划分；Schema 的路径模式只做基础格式筛查。
- Snapshot 内容集合、摘要、字节数、文件类型/mode、相对真实文件系统和 Run/Session 身份一致性。

## 批次 3

review-artifact-metadata-v1、review-artifact-content-page-v1、review-tool-envelope-v1与五种review-tool-*-parameters-v1来自生产Pydantic严格模型，测试核对文件与模型生成结果一致。正反例在contracts/fixtures/review-tools-v1/examples.json；字节预算、文件身份/哈希、Cursor签名、权限和JSON中的状态一致性仍由应用层验证。

## 批次 4

review-graph-state-v1、review-context-manifest-v1、review-compaction-summary-v1来自生产严格模型，正反例见contracts/fixtures/review-context-v1/examples.json。Artifact metadata新增本批实际消费的CONTEXT_MANIFEST/COMPACTION_SUMMARY kind。模型接口没有公共HTTP端点；这些Schema用于内部状态及诊断Artifact，不提供恢复或继承权限。Tool配对、来源哈希/归属、连续范围、请求预算/摘要进展、字节上限与提交确认由应用层重复验证。test-only-utf8-units不是生产Token计量；Evidence/Candidate非空引用本批拒绝。
