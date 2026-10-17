# 批次 5 固定离线 Eval

执行日期：2026-10-11。种子：`services/review-harness/tests/fixtures/review-eval-v1.json`。
验收命令：服务目录运行 `python -m pytest -q tests/integration/test_real_review.py`。
本组包括 24 条实际 Runtime/Store 回归；分级、反例过滤和原子读回均有实际断言。
最终完整矩阵与新鲜计数见 `review-harness-batch-5-development-handoff.md`。

| 种子 | 原始材料/提交的判断 | 预期 | 实际 | 变体 |
|---|---|---|---|---|
| nullable | `value(Integer x)` 直接调用 `x.intValue()`，触发 `value(null)` | 1 VERIFIED | 1 VERIFIED | 6 |
| guarded | 同一解引用前有 null guard，脚本仍提交空指针候选，独立验证确认反例成立 | 0 Finding | 0 Finding | 6 |
| quantitative | 无运行生产者，却宣称路径增加 10 ms 延迟 | 1 RISK，不能 VERIFIED | 1 RISK | 6 |
| mislabelled-performance | 相同性能量化声明，自报 STATIC_CORRECTNESS | 1 RISK，不能 VERIFIED | 1 RISK | 6 |

每个种子分别覆盖：小文件/11 KiB 填充、整页/源码分段读取，以及低捕获上限。低上限时即使
已读完 Artifact，Pass 也必须 INCOMPLETE；未捕获的源码不能计为 FULL。大正文先可靠转存，再由模型
按原 Provider ID 调用 Artifact 分页；读完之前不计完整覆盖。验证重新打开原材料。
Result 在资源清理后提交；模拟提交后丢确认，再重复同一提交，只有一个 Result 和成功事件。

对于固定的 nullable/guarded 脚本路径，VERIFIED 层面的已知静态缺陷误报 0、漏报 0。
两条性能样例验证的是等级门禁，不据此声称其性能判断成立。

这些响应由离线脚本产生，文件、SQLite、Artifact、Checkpoint、Gateway、Meter 和 Store 使用实际实现。
此结果不衡量 DeepSeek 自主发现问题的精度/召回率，也不证明真实模型抗 Prompt Injection 的质量。
真实 Provider 小样尚未授权执行；不得把成功的 API/包交付率当成审查质量指标。
