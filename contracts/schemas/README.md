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

字段语义、HTTP 状态、事务和恢复规则以 ADR-0009 为准；Snapshot 身份以 ADR-0014 为准；Result 内部 Evidence/Finding 演进以 ADR-0015 为准。

JSON Schema 无法表达或不适合表达的规则由应用层和契约测试负责，包括：

- 请求和字符串的 UTF-8 字节上限；
- `input.message` 至少包含一个非空白字符；
- Result `byteSize` 等于 Markdown 的 UTF-8 字节数；
- 时间先后关系；
- Result/Run/Snapshot 身份完全一致；
- 事件序号连续性和数据库事务原子性。
