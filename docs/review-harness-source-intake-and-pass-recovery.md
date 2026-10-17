# 试用可用性补修：来源提交简化与 Pass 故障隔离

日期：2026-10-17。依据：本次手动试用 Run `623d7491-89d5-426f-826f-467159e0aa04` 的离线重放，以及 Codex 简单代码位置输出、DeerFlow 局部错误结果化的实现对照。不宣称外部项目提供相同审计保障或语义质量已经验证。

## 实现

### 1. 模型不再手工维护 Evidence 关联

State 5/6 的新调查请求使用 `SOURCE_V1`：候选只填写 `location` 和可选 `supportingSources`（来源句柄与行区间），不再填写 `evidenceCandidates`、`localKey`、`evidenceLinks`、`codeSelections`。候选的缺陷描述/触发依据仍由模型提出，平台不生成语义结论。

平台编译这些显式选择，按候选生成 CODE/DOMAIN_CONTEXT Evidence，并连接内部关联，再进入既有 `prepare_local_submission`、原文重读、整批预校验、原子 Evidence 保存和 Checkpoint 确认流程。没有扫描整个 Run 猜测证据，没有按相似文本扩大授权。

新输入每次最多 8 个候选，每候选最多 16 个来源选择，最多生成 16 个 Evidence（每候选最多两类），沿用原单次 Evidence 上限；需要更多候选可分次 CONTINUE。没有增加工具累计次数上限，也没有提高费用或纠错额度。

新 Schema 单独增加 `review-source-investigation-v1`。State 版本、Result 2/3、既有内部 Schema 和历史记录不改写；没有 submissionVersion 的旧局部协议仍按旧严格类型读取。显式 SOURCE_V1 格式错误不能降级为旧格式绕过校验。

### 2. 局部模型失败不再抹掉前序 Pass 成果

多 Pass 编排只捕获明确的 `SubmissionIssue`：既有两次纠错耗尽后，该 Pass 以 `MODEL_OUTPUT_REJECTED_<原错误码>` 收敛为 INCOMPLETE，未验证候选不成为 Finding，不再额外调用验证模型；后续所选 Pass 可以继续，前序已完成 Finding 保留。

最终 Result 如实标为 SELECTED_INCOMPLETE，不把交付成功解释为审查全部通过。当前隔离粒度是整个 Pass，不是“自动采纳批次中的合法子集”；没有放松原子保存要求。

普通 HarnessError 不走该恢复路径：存储/Checkpoint 失确认、身份与完整性故障、资源未知、未知费用、取消等继续按原保护终止。单 Pass 历史失败策略未一并改写。

### 3. 诊断可见性

Java 只读视图可从已持久并通过原协议验证的 REVIEW_RUN_FAILED 事件投影失败码，在本地 diagnosticCode 缺省时补齐页面展示；不输出内部失败消息，不改历史快照/事件。

State 5/6 区分模型自报 `REVIEW_COVERAGE_PARTIAL` 和平台实际发现的 `SOURCE_COVERAGE_PARTIAL`。读完不等于审查充分；静态未执行测试也不直接等于源码缺页。State 早期版本的原因码行为保留。

## 离线证据

- 仓库外只读真实重放：旧提交稳定报 candidates[1] REFERENCE_NOT_IN_EVIDENCE；同样的候选位置经 SOURCE_V1 编译后通过生产引用门禁。仅替代 Evidence 原文/存储边界，不冒称新 Provider 全链路通过；原 Checkpoint/数据库未改。
- 新入口 HTTP 回归接通真实 SQLite、Snapshot、Evidence、Checkpoint、脚本 Provider、独立验证和 Result；Provider 网络为 Mock，零付费。
- 非法 JSON 耗尽纠错的多 Pass 回归：当前 Pass INCOMPLETE，后续 Pass 完成；带有效 Finding 的变体保留其余五 Pass 的贡献。
- 新入口存储失败、Checkpoint 不确认回归仍 FAILED，无 Result，资源释放；多 Pass 存储不确定异常仍上抛，不继续执行后续 Pass。
- Java 失败码投影覆盖 HTTP 协议 1.0/1.1，不暴露内部消息、不重写历史。

新鲜验证（各组有重叠，不相加）：

- 原有局部句柄/多 Pass/新编译器首轮矩阵：60 项通过。
- SOURCE 编译器、位置 API、费用账本矩阵：58 项通过。
- 最终关键行为筛选（source/self-partial/submission-rejected/candidate-rejected/fatal-storage）：15 项通过。
- 新入口独立补充：SOURCE_V1 六 Pass HTTP 闭环 1 项；新入口存储与 Checkpoint 故障 2 项通过。
- 新模型拒绝隔离与保留五 Pass Finding 回归分别通过；存储不确定异常不降级回归通过。
- Schema/多 Pass 合同专项 51 项通过；73 份 Schema 元校验通过。
- Java `mvn.cmd -q -pl services/control-plane '-Dtest=Review*Test' test` 通过，JUnit 汇总 127 项、零失败/错误/跳过；最终失败码投影专项重跑 2 项通过。
- Ruff check/format 与差异检查通过。未声称执行 Java 格式化器。

主要 Python 命令在 `services/review-harness` 执行：

```powershell
.\.venv\Scripts\python.exe -m pytest tests/integration/test_source_submission.py tests/integration/test_position_review.py tests/integration/test_fee_ledger.py -q
.\.venv\Scripts\python.exe -m pytest tests/integration/test_source_submission.py tests/integration/test_position_review.py tests/integration/test_multipass_runtime.py -k 'source or self-partial or submission-rejected or candidate-rejected or fatal-storage' -q
.\.venv\Scripts\python.exe -m pytest tests/integration/test_position_review.py -k multipass-source-intake -q
```

最终较大受影响矩阵：157 项通过（536.46 秒，退出码 0），覆盖 multipass_runtime、source_submission、submission_feedback、json_tail_feedback、real_review、real_context、review_fact_correction、multipass_contracts。该组收集后追加的 fatal-storage 用例另行执行通过，未混入 157 的计数。各组重叠，不累加。

Python 全量、干净安装、POSIX 与真实模型效果不以这些专项代替；UI 本轮未改源码，其前序测试不作为本轮新鲜浏览器证据。

## 交付边界

本轮没有付费请求、Coding、提交或推送。未改动历史真实 Result；前端重构等既有未提交内容保留。常驻试用服务尚未切换到本次实现，不应在旧进程上验证新策略。

本轮没有将验证/去重协议全部改成自由文本，也没有取消 Schema 或来源门禁。新的输入位置装配不保证模型判断正确；后续真实试用仍须区分执行闭环、有效缺陷及误报。
