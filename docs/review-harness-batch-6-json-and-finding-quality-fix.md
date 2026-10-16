# 批次 6：JSON 纠错反馈与 Finding 资格最小补修

日期：2026-10-16，Python 3.12.6；基线 `30a0054f7ab3ec8ed865ce2ff0f18ccd25d7809a`。
依据：仓库外冻结稿64、补修复审66、独立复核67。本轮离线，未使用密钥或发送付费请求；不运行真实CC，不提交推送，不进入下一批。

## 可复核定位

原Run `aec9c177-2b77-451c-869c-c20d7550c264` 的15请求、管理支出¥0.278958、FAILED/RELEASED、无Result保持。历史Ledger和费用未修改。

证据根 `C:/Users/ROG/AppData/Local/Temp/code-weave-b6-retest-_4vctj40`；新增诊断位于 `offline-review-67/`。只读SQLite使用 `mode=ro`。`historical-file-digests.json` 保存历史数据库/状态/调用/账本的当前摘要。

用基线30a0054指令、生产ContextEngine.build / Adapter.build_request、Checkpoint、原Evidence和固定Snapshot离线重建三次consistency验证请求；仅在离线Builder提供固定anchors代替运行期workspace/状态检查，不启动旧Run、不触发invoke。重建字节的摘要与原调用持久requestDigest逐一完全一致，因此不是凭相似消息推断实际请求。

| 请求 | State revision | 本地Token（原/重建） | 消息数 | requestDigest（sha256） |
|---|---:|---:|---:|---|
| 初次验证 | 52 | 7485 / 7485 | 4 | 964142bd8b06094cc89e69b53a53b9813e970d2aaeb9f9d6617dbd17cf712edd |
| 第一次纠错 | 56 | 9322 / 9322 | 6 | e58ecc2cd869488f331584e99837e81bc9648432d00e89e6b2b3f00e8bc867ea |
| 第二次纠错 | 60 | 11105 / 11105 | 8 | f1d4fa2ae32b3f1ed8600cc35ed6d6e89a133ad0316004a01f42659508b43f26 |

三次均为json_object输出模式；验证采用隔离原始材料块，未夹带调查工具半对话，无摘要压缩。原始材料与候选完整；两次完整脱敏拒绝正文均续接。原反馈仅JSON_INVALID，无Extra data、位置或明确单对象要求。没有证据把故障归因于材料漏送或请求截断。Provider原始响应信封未保存，不能核实finish_reason/原始传输层内容，保留未知。

复现入口（从项目根执行，先将服务目录加入PYTHONPATH）：

```powershell
$env:PYTHONPATH='D:/personal_project/coding-review-platform/services/review-harness'
services/review-harness/.venv/Scripts/python.exe C:/Users/ROG/AppData/Local/Temp/code-weave-b6-retest-_4vctj40/reconstruct_67.py
services/review-harness/.venv/Scripts/python.exe C:/Users/ROG/AppData/Local/Temp/code-weave-b6-retest-_4vctj40/replay_feedback_67.py
```

`reconstruction.json`、三份request-N.json为重建材料；`replay-feedback.json`及三份rejected-N-redacted.txt为受管脱敏副本。三份正文经过生产parse_submission仍全部拒绝，Extra data零基字符位置2883/5144/5145；本合成输入副本redactionState均NONE。无截尾采纳、无前缀发布。

## 最小实现差异

- `runtime/review.py` 捕获JSONDecodeError，只投影EXTRA_DATA或SYNTAX_INVALID及原文行/列/字符位置；不复制异常原消息或输入片段。重复键、非有限数及超限仍严格拒绝，保持通用安全反馈。
- `domain/submission.py` 让既有SubmissionIssue携带并传递安全jsonError诊断，原纠错Artifact和下一请求共用，不新增存储系统或Result字段。
- JSON阶段纠错明确要求恰好一个完整对象、无尾部/第二对象/Markdown围栏，并说明坐标基于原提交。完整脱敏拒绝回复继续保留。两次额度、严格解析、Schema及失败停止逻辑不变。
- 调查提交与独立验证共享Finding资格说明：事实准确不等于缺陷成立；VERIFIED须有违反约束、具体触发和负面后果；良性观察在调查中作为检查/排除记录，若进入验证则REJECTED。RISK须有合理负面后果和未确认环境前提；SUGGESTION须有明确改进目标与价值，不把所有可不改观察自动降级。判断完整主张而非关键词，不新增结构字段或自动翻转disposition。

## 离线验收

先红后绿：生产API/Provider请求边界新增JSON尾部纠错两场景，原实现2项失败（实际请求缺jsonError）；资格五场景原实现5项失败（实际验证请求缺资格规则），补修后通过。未替换生产执行器；HTTP边界使用MockTransport，不调用真实Provider。

命令均使用服务.venv的Python，日期2026-10-16：

| 检查 | 当前结果 |
|---|---|
| pytest tests/integration/{test_json_tail_feedback,test_review_fact_correction,test_submission_feedback,test_local_scope_feedback,test_local_handles,test_diff_registry}.py -q --tb=short | 71通过，227.97秒 |
| pytest tests/integration/test_real_schema_fixtures.py tests/unit/test_submission_json_diagnostics.py -q --tb=short | 19通过，1.06秒（含7项诊断） |
| 最后仅将局部变量scope_feedback重命名为correction_feedback后，重跑JSON调查/验证请求边界（-k 'json_tail or json-tail'） | 4通过，26未选，19.83秒；未计入不重复用例总数 |
| pytest tests/integration/{test_multipass_runtime,test_compatible_token_budget,test_fee_ledger}.py -q --tb=short | 91通过，173.13秒 |
| Ruff check review_harness tests | 通过 |
| Ruff format --check review_harness tests | 135文件通过 |
| git diff --check | 通过 |

覆盖良性重构REJECTED、真实null回归VERIFIED、环境风险RISK、有价值的预防改进SUGGESTION、引用“no correctness risk”但仍真实描述null缺陷的VERIFIED；覆盖调查与验证JSON尾部的修复/持续失败，两次额度耗尽后无Result、资源RELEASED；覆盖多行与中文字符坐标、重复键/非有限数/捕获限额不放宽。事实修正、身份/引用/哈希/候选专属来源测试沿用。

## 限制与设计窗口复审事项

本次资格修复是模型指令和既有disposition处理约束，不是确定性语义分类器。脚本响应证明生产请求含规则、平台正确处理REJECTED/RISK/SUGGESTION/VERIFIED，不证明真实模型一定判对，也不证明同模型独立验证具有独立真值。若需新增资格结构字段，应先冻结设计，本次未自行扩Schema。JSON反馈增强也不保证下一次真实模型能改好非法尾部。

原六Pass真实验收仍失败；未生成新真实Result，未重验Java导入/UI/Handoff。没有Java/前端/Schema产品修改，既往Java/UI离线证据保留为历史；本次Python提示词/诊断改动使旧Python全量不能冒称最终工作区全量，本轮仅按影响跑上述专项，未重跑全量/干净环境/Legacy。等待设计窗口独立复审，不自动付费重跑。

## 复现命令与完成时间

以下在 `D:/personal_project/coding-review-platform/services/review-harness` 执行（前文表格花括号仅为压缩展示文件组）：

```powershell
.venv/Scripts/python.exe -m pytest tests/integration/test_json_tail_feedback.py tests/integration/test_review_fact_correction.py tests/integration/test_submission_feedback.py tests/integration/test_local_scope_feedback.py tests/integration/test_local_handles.py tests/integration/test_diff_registry.py -q --tb=short
.venv/Scripts/python.exe -m pytest tests/integration/test_real_schema_fixtures.py tests/unit/test_submission_json_diagnostics.py -q --tb=short
.venv/Scripts/python.exe -m pytest tests/integration/test_multipass_runtime.py tests/integration/test_compatible_token_budget.py tests/integration/test_fee_ledger.py -q --tb=short
.venv/Scripts/python.exe -m pytest tests/integration/test_json_tail_feedback.py tests/integration/test_review_fact_correction.py -q -k 'json_tail or json-tail' --tb=short
.venv/Scripts/python.exe -m ruff check review_harness tests
.venv/Scripts/python.exe -m ruff format --check review_harness tests
git diff --check
```

截至2026-10-16 17:08 +08:00，共181项不重复用例通过，另4项JSON请求边界重复核对通过。补修已完成，R02/R03待独立复审，未宣称关闭或通过真实验收。
