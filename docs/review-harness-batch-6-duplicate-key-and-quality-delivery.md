# 批次 6：重复键诊断与 c11 质量回归离线交付

2026-10-16，基线 `6ef9a018a06f50b0acb1ae437cf3a8833edc41ec` 加既有已复审、未提交预分区改动。依据 `review-harness-batch-6-duplicate-key-and-quality-freeze.md` 实施。未调用付费模型、未读密钥、未运行 CC、未提交或推送。

## A：工程补修

仅两个产品文件发生本轮修改：

- `domain/provider.py`：重复键抛 `DuplicateJSONKeyError(ValueError)`，固定异常消息，不携带未知键名或值。其他调用方原有 ValueError 捕获兼容。
- `runtime/review.py`：在通用拒绝之前投影 `jsonError.errorType=DUPLICATE_KEY`，仍为 `JSON_INVALID / JSON / PROVIDER_STRUCTURED_OUTPUT_INVALID`，不伪造坐标。下一纠错请求明确同一对象键唯一、同值重复也非法、重生成完整对象、不能 first/last wins、不同对象同名合法。

不变：严格解析、身份/来源门禁、两次共享纠错、去重 10 次/600 秒、位置协议、预分区、外部 Result/Schema。既有 `runtime/dedup.py` 工作区差异属于前序预分区补修，本轮未编辑。

生产 HTTP 边界测试证明：脱敏拒绝正文和具体诊断进入实际下一请求；修正完整对象可以采纳；持续重复三次回复后停止，保留原 Finding；重复键修正后缺失/多余/重复句柄仍拒绝。MockTransport 为离线脚本，只验证工程行为，不证明模型能纠错。

## B：质量基准与能力边界

`tests/fixtures/c11-known-quality-failure.json` 保存原 Run、原候选、原 Finding、原 SUPPORTED 验证与来源绑定、源码和调查观察；标记 `KNOWN_MODEL_QUALITY_FAILURE`。原候选来自 028 请求，原验证和不可变 Result 未修改。基准测试核对原候选/原 Finding claim 一致、SUPPORTED/VERIFIED 且未修正，以及实际源码 SHA 与授权 CODE 来源匹配。

源码表明两方法对 null 均抛 NPE，但显式 requireNonNull 与隐式解引用的机制不同；不能推导异常消息等所有运行维度相同。nullableLength 仍违反保留的 null 返回零契约。历史“opposite null handling”和自然语言行号偏差仍如实保留。

生产位置协议测试把原 c11 claim 与原源码重新绑定到隔离测试 Run 的来源：脚本 CORRECTED 能输出修正后的有效 Finding；脚本 CORE_CONTRADICTED + REJECTED 不生成 VERIFIED。既有核心反例测试继续覆盖源码实际具有 null guard 的情况。上述测试只证明既有修正/拒绝通道，不是确定性语义识别器，不代表历史模型质量问题已解决。未追加调查/验证提示词、模型、关键词裁决、重试或 Schema。

`tests/fixtures/dedup-real-duplicate-keys.json` 保存真实 035/037/038 脱敏回复正文，当前解析继续拒绝，仅安全诊断更具体。不能追认原 Run 的失败比较成功。

## 新鲜验证

在仓库根使用 `services/review-harness/.venv/Scripts/python.exe`，Python 3.12。先红测：`pytest tests/unit/test_submission_json_diagnostics.py tests/integration/test_dedup_fix.py -k duplicate_keys -q`（路径加 services/review-harness 前缀），6 项失败，均为新增安全分类/反馈缺失，之后才修改产品代码。

受影响回归命令（路径均相对 services/review-harness）：

| 命令参数 | 结果 |
| --- | --- |
| `-m pytest tests/unit/test_submission_json_diagnostics.py tests/integration/test_dedup_fix.py -q` | 阶段回归 50 通过；此后新增边界另跑，不冒称最终全集 |
| `-m pytest tests/integration/test_dedup_fix.py -k duplicate_keys -q` | 最终五种 HTTP 边界 5 通过，37 未选 |
| `-m pytest tests/unit/test_submission_json_diagnostics.py tests/unit/test_position_validation.py tests/unit/test_position_schemas.py tests/integration/test_json_tail_feedback.py tests/integration/test_submission_feedback.py tests/integration/test_deepseek_adapter.py -q` | 108 通过 |
| `-m pytest tests/unit/test_submission_json_diagnostics.py tests/integration/test_position_review.py tests/integration/test_review_fact_correction.py tests/integration/test_dedup_partition.py -q` | 83 通过（420.54 秒） |
| `-m pytest tests/unit/test_submission_json_diagnostics.py tests/integration/test_position_review.py -k 'c11 or historical_duplicate or duplicate_exception' -q` | 最后调整 c11 测试后 5 通过，27 未选 |
| `-m pytest tests/unit/test_submission_json_diagnostics.py -q` | 最终补齐来源 SHA 断言后 14 通过 |
| `-m ruff check review_harness tests` | 通过 |
| `-m ruff format --check review_harness tests` | 144 文件符合格式 |
| `git diff --check` | 通过；既有验收文档有 LF/CRLF 提示，无差异错误 |

不同命令有重叠，不累加为唯一测试总数。本轮不重跑 Python 全量、干净环境、Java/Legacy、UI/Legacy；产品改动仅限内部错误分类及已有 JSON 纠错反馈，合同未变，位置 Schema 专项已运行。历史矩阵不冒称当前结果。

## 自查及下一步

主 Agent 对抗式自查了专用异常的捕获顺序、不泄密投影、同值/嵌套/数组/Unicode 转义重复、独立对象合法性、原真实拒绝原文、持续错误额度及修正后句柄校验；另保留语法坐标、尾部、非有限数、捕获上限回归。未引入宽松解析或自动修剪。

交设计窗口分别复审 A 工程补修与 B 质量基准。A 的真实模型纠错效果未验，c11 模型语义质量未解决；不授权付费重跑，不进入下一批。本轮仓库外日志采用字节追加并核对追加前前缀，只证明本次历史前缀保持，不恢复或覆盖更早已知历史字节问题。

最终检查时间：2026-10-16T22:40:14+08:00。所有上述命令均零退出；未运行项如前述。
