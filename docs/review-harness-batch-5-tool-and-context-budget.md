# 工具总数与256K上下文预算调整


## 2026-10-12 用户调整：取消工具累计硬上限，默认256K窗口

用户明确取消所有工具累计次数的限制，并要求提高Token预算，随后指定默认256K与接近大窗口前压缩。此授权替代冻结25/40中的32次工具及32K操作窗口；不修改其他安全边界，不进入批次6。

当前策略deepseek-compatible-budget-v2：W=262144（256×1024），O=4096，P=1024，B=257024。U仍max(1024,ceil(E/10))，C=E+U；C≤B才发送。已存在闭合可压缩历史且C>0.8B时自动压缩；80%为工程软水位，不等到耗尽窗口。边界C205619无需压缩，C205620触发；E233658+C余量可恰好放入B，E233659拒绝。摘要、调查、提交和验证共用，压缩须严格减少Token且最终预算内，平台锚点/配对/Checkpointer保护保留。

Provider档案仍引用已核对的官方1M容量来源，256K为本平台自定操作窗口，不宣称重新认证Provider最大容量。本文不把用户Codex 500K/475K的5%显示差异当作已知实现依据；本次官方配置文档无法读取，原因未确认。余量不是数学保证，超窗恢复保持DISABLED。

取消graph_max_tool_calls配置和Context的累计次数阻断；State2.0的toolCallCount与providerCallMappings去除总量数值约束，仍非负/唯一/实际计数一致。每响应最多8工具、模型调用16、图步/墙钟、Token、输出与Checkpoint字节等现有限制不变，故“不限制工具总数”不等于无限时长或无限模型请求。旧32/64配置不再支持，新启动不得继续使用旧参数。工具预算耗尽的既有分支不再从新生产入口触发，旧失败原事实保留。

预算历史兼容：旧1.0/1.1 Manifest严格读取，新Manifest1.2绑定v2及B257024；旧ModelCall缺省字段/策略v1仍按旧B27648校验，不重写原字节。ModelCall新v2数值上限同步扩大。Result2.0结构不变，累计inputTokens最大范围扩大为16×262144，Python和共享Schema同步，Java仍使用同一Schema。旧真实Result保留，无手改不可变产物。Tokenizer、Evidence、候选、factChecks不改。

真实烟测费用预留随新输入预算同步为每请求最坏0.546816元（现有峰值输入2、输出8元每百万的管理策略），16请求全量最坏8.749056元；不自动视作8元预算可承受全量最坏。本轮零API调用，下次付费必须基于最新价格、现有累计费用和逐请求预留重新核对，不重用旧1.409024元预留描述。

离线回归新增超过64/80历史工具计数仍能规范续接与持久、40K输入及Usage、真正超预算先保存Usage再拒绝、Manifest版本/Schema、256K软水位、原ID配对和取消。最终新鲜矩阵结果完成后追加；中间64K运行不是最终256K证据。Java共享合同专项5通过，预算数值更改后已重跑。保持未提交、未推送，先离线复审。


## 最终新鲜验收

最终256K版本：

- Python受影响矩阵：`python -m pytest tests/integration/test_real_context.py tests/integration/test_compatible_token_budget.py tests/integration/test_deepseek_adapter.py tests/integration/test_real_schema_fixtures.py tests/integration/test_model_calls.py tests/integration/test_compaction.py tests/integration/test_manual_paid_budget.py tests/integration/test_real_review.py tests/integration/test_real_contracts.py tests/integration/test_real_enablement.py tests/integration/test_provider_http.py tests/integration/test_deepseek_meter.py -q --tb=short`，177通过，242.55秒。
- 收尾新增硬预算恰好放入/多1Token回归后，`python -m pytest tests/integration/test_compatible_token_budget.py -q --tb=short`，30通过，4.27秒。与上组重叠，不累计报告为207。Mock初稿使用预消费的httpx.Response导致StreamConsumed，已改为ByteStream，生产代码未因此改变。
- Java：控制平面目录`../../mvnw.cmd '-Dtest=ReviewResultV2Test,ReviewRealContractTest' test`，5通过，failure/error/skip均0，5.763秒，256K共享Result Schema已打包。
- Ruff、格式110文件、`git diff --check`通过。
- 独立只读探针：旧ModelCall fixture canonical字节保持；v2接受E100000/C110000，改标v1拒绝，证明不将历史策略放宽为新窗口。

回归覆盖：原ProviderID、80个已有工具映射仍可续接、40K输入/告警/持久、超过新操作预算先存Usage后停止、最小输入三目的零发送、历史漂移App重建、旧Manifest1.0/1.1与新1.2严格读取、Schema、压缩来源/无进展/Checkpoint故障及取消、实际费用预留调整后未知Usage不重放。

没有全量Python、干净环境、Java全量/Legacy或UI重跑，不把历史矩阵冒充当前。之前真实Result与验收产物未更改；本次256K尚无付费实测，也不证明事实冲突质量或完整Finding已通过。已获得用户取消工具总数和默认256K的明确授权，但仍须设计窗口独立复审；未提交、未推送、不进入批次6。
