# 批次6A2：持久费用账本交付

基线 `1b24f3a`；依据冻结稿51。仅离线开发、待独立复审；未提交、未推送、未调用收费模型或真实Coding。保留output、原架构可视化、真实Result及历史失败产物。

## 实现与可复现入口

Python `FeeLedger` 在 SQLite 写锁及 BEGIN IMMEDIATE 中保存费用授权、ModelCall及逐调用预留。金额为整数微元（1元=1000000微元），价格为严格定点字符串并向上取整；不使用浮点金额。授权来自持久Run的完整ReviewPlan，不接受模型授权；authorizationId不可跨Run复用。

PREPARED与预留同事务；可靠确认DISPATCHING后才发送并计数；Usage与SETTLED同事务。确认查询同时核对预算、调用、预留全部事实。提交后响应丢失可精确确认，提交前失败零发送。超预留先保存真实Usage/管理费用/overrun，再停止，不回填预算或执行返回工具。PREPARED在终止时退款；DISPATCHING未知保留预留、停止该Run，不自动重发。费用UNKNOWN不把已释放本地资源写成UNCERTAIN，不污染其他Session。

生产ModelCallStore/Context入口路由新费用事实；事务任务按实际完成状态跟踪，取消不假定SQL已回滚或释放。原1.0调用仍按旧合同读写，2.0费用调用绑定1.1 Run；新协议不能绕过账本走旧_save。Run/Pass/汇总期限持久保存，发送前再次核对；HTTP timeout取120秒、Run剩余、当前阶段剩余三者最小值。冻结300/2000/10计数，不借用下一阶段额度，不新增工具累计上限。

迁移 `0007_fee_ledger` 增加两张费用表；Readiness访问其所有必需列，启动恢复后审计金额、调用状态、孤儿与摘要完整性。存在费用事实时拒绝破坏性降级。部署需操作员显式迁移，应用不自动修补。

操作员以绝对路径配置 `CODE_WEAVE_REVIEW_PRICE_PROFILE_PATH`，提供已核实、带官方来源URL/来源文档hash/核实时间/到期时间的PriceProfile；缺失、过期、损坏拒绝新付费能力。认证GET `/internal/v1/review-price-profile` 返回同一profile和摘要，Java严格检查Schema、时间及摘要。发送前当前profile摘要变化拒绝旧Run，不偷偷重定价。离线fixtures为合成价格，**本轮未联网核实真实价格，不可直接用于收费授权**。

合同生成入口：服务目录 `python -m tests.export_fee_contracts`；新增5份独立Schema与正反例，旧Schema/fixtures未改。复现入口：`tests/integration/test_fee_ledger.py`（真实SQLite、生产Context和DeepSeekAdapter MockTransport，HTTP调用为模拟）。

## 验收记录

最终新鲜结果及精确时间见本文件后续验收表。完整命令、UTC起止、退出码和日志保存在 `C:/Users/ROG/AppData/Local/Temp/code-weave-b6a2-wno727op`。较早203项受影响回归在最终取消补修之前，只作为历史，不代替最终证据。

## 仍未实现或未验

- HTTP1.1 POST继续明确拒绝；费用能力可READY，整体多Pass仍NOT_READY。新授权账本尚未由公开启动路径初始化。
- 六Pass串行编排、State4、Pass生命周期事件、3.0原子交付/Java导入属于6A3。当前图仍沿用原16模型调用等旧执行约束；本切片不宣称生产已经执行300/2000次。
- 6A2提供持久期限及请求timeout，完整Pass/Run监督和预算耗尽后六维收尾在6A3接通。旧1.0/直接Adapter调用不是新正式费用授权路径。
- 管理价格按保守输入单价计费，可覆盖缓存命中；管理消费不是Provider实际账单，也不是远端绝对财务封顶。远端超预留可产生真实overrun，必须保留事实。
- 本轮只受影响测试，未跑Python全量/干净环境、Java正式/Legacy全量、UI矩阵；前端未改。历史public偏差、同模型验证局限、256K及POSIX未验、超窗恢复DISABLED仍保留。

请设计窗口独立复审发送零调用、事务失确认、Usage/overrun、未知保留与资源分离、价格绑定、时限/计数和旧读回。复审后由用户另授权提交；不进入6A3。


## 最终新鲜验收表（2026-10-13，+08:00）

| 验收 | 结果 | 时间/依据 |
| --- | --- | --- |
| Python受影响矩阵 | 217通过，116.37秒 | 19:37:19—19:39:17；python-affected.json/log |
| 最终费用专项 | 29通过，40.08秒 | 19:39:44—19:40:26；fee-final.json/log；仅测试函数换行格式后重跑 |
| Java新旧合同专项 | 13通过，0失败/错误/跳过 | 19:34:11—19:34:17；Maven5.141秒；java-contract.json/log；未使用旧XML计数 |
| Schema总集 | 45份元校验、192处本地引用通过 | 19:37:48；schema.json |
| Ruff / 格式 | 全部通过；135文件 | 19:40:26；ruff-final/format-final.json/log |
| 差异/基线 | 通过；HEAD仍1b24f3a，暂存区为空 | git diff --check / git rev-parse / git diff --cached |
| 全量、干净3.12、Java/Legacy全量、UI | 本轮未验 | 本轮为切片受影响验收，无前端改动 |
| 付费/真实多Pass | 未验 | 无网络模型请求，HTTP1.1未启用 |

Python在services/review-harness执行：
```powershell
.\.venv\Scripts\python.exe -m pytest tests/integration/test_fee_ledger.py tests/integration/test_model_calls.py tests/integration/test_real_context.py tests/integration/test_compatible_token_budget.py tests/integration/test_real_schema_fixtures.py tests/integration/test_multipass_contracts.py tests/integration/test_schema_readiness.py tests/integration/test_contracts.py tests/integration/test_provider_http.py tests/integration/test_deepseek_adapter.py tests/integration/test_real_enablement.py tests/integration/test_workspace_recovery.py -q --tb=short --basetemp C:/Users/ROG/AppData/Local/Temp/code-weave-b6a2-wno727op/pytest
.\.venv\Scripts\python.exe -m pytest tests/integration/test_fee_ledger.py -q --tb=short
.\.venv\Scripts\python.exe -m ruff check .
.\.venv\Scripts\python.exe -m ruff format --check .
```
Java在services/control-plane执行：
```powershell
../../mvnw.cmd -Dtest=ReviewFeeContractTest,ReviewMultipassContractTest,ReviewRealContractTest,ReviewResultV2Test,ReviewCapabilityNegotiationTest test
```

中间一次格式检查因新增测试长函数声明失败，已格式化并重新检查通过；补充测试初稿的Failure类型、授权重复和迁移URL配置错误已修正。无生产测试失败遗留。29专项包含于217矩阵，不将两者相加冒充246个独立用例。


## 复审53的最小补修（待独立复审）

B6A2-R01：生产Context将prepare及发送前active/build/请求一致性检查纳入同一退出处理。真实IO退出后独立读取调用事实：没有意图则保持原错误；存在意图时沿用ModelCallStore.close/FeeLedger.close原子状态规则。PREPARED立即LOCAL_CLOSED/REFUNDED；DISPATCHING仍UNKNOWN并保留预留，不凭取消猜测未发送。关闭事务使用独立清理信号，用户取消不会跳过收尾；仍由原任务跟踪、清理期限及未知资源保护约束，尚未退出的SQL不关闭、不假退款。无新退款实体或重试机制。

B6A2-R02：feeReservation=READY新增依赖启动recovery_complete事实；该事实仅在恢复、费用审计与材料审计成功后置true。每个能力请求不重新扫描账本；价格/Schema/写入检查保留，总体多Pass仍NOT_READY。

补修仅涉及runtime/context.py、api/routes.py和正式费用回归（另加交付/仓库外日志）。无Java/UI、Schema/迁移改动，原Java13项与Schema45/192证据保留为补修前证据，不冒称本轮新鲜重跑。离线红测先复现两个问题；正式回归补取消/重建异常/请求变化三路径以及审计失败，正常审计READY正例和真实事务取消保护继续覆盖。既有取消事务测试改为要求本次退出已退款，删除依赖重启才能满足断言的旧预期。


补修新鲜验收：受影响矩阵221通过（131.05秒；UTC 2026-10-13T12:19:07.047068+00:00—2026-10-13T12:21:20.263896+00:00），Ruff通过、135文件格式通过。矩阵开始后补加“ModelCallStore缺失时不进入调用关闭”的保护条件，因此最终Context/费用/兼容预算三个受影响模块另重跑85通过（94.09秒；UTC 2026-10-13T12:20:17.632088+00:00—2026-10-13T12:21:53.901217+00:00）。85包含于221，不累计。原独立复现探针两项本地复跑通过（3.52秒）；独立复审结论仍须由设计窗口给出。

日志根：C:/Users/ROG/AppData/Local/Temp/code-weave-b6a2-fixes-3kvzw1fa。矩阵命令沿用上文12模块；最终补跑命令为服务目录 `.venv/Scripts/python.exe -m pytest tests/integration/test_fee_ledger.py tests/integration/test_real_context.py tests/integration/test_compatible_token_budget.py -q --tb=short`。正式费用模块当前33项；本次不宣称全量/干净环境/Java/UI/Schema新鲜验证。无付费、未暂存/提交/推送、HEAD保持1b24f3a；不进入6A3。


## 独立复审54通过

2026-10-13 20:26 +08:00：设计窗口确认B6A2-R01/R02关闭，达到本切片提交条件。独立受影响85项通过，扩展探针3项通过（含原2项），验证DISPATCHING即使观察到零HTTP也保留UNKNOWN预留；Ruff/135文件格式/差异通过。记录位于仓库外54-batch-6a2-fee-ledger-fix-review.md。本记录不将独立与开发测试相加，不宣称真实收费或多Pass已开放。用户建议先提交本切片；不推送，6A3另行推进。
