# 批次6A3：串行Pass与Result3交付（待独立复审）

基线 `2abdd1d`；冻结稿51。延续中断前的未提交实现，核对工作区后完成离线开发与受影响验收。未付费、未启动真实Coding、未提交/推送；output、原架构可视化及不可变历史Result保持。

## 实现范围

State4独立Typed合同及Checkpointer历史读回：当前Pass独立调查消息、摘要、inspection与计数，Run内共享受信来源/Evidence/候选Registry与有界前序PassLedger。进入新Pass重置当前视图；同一材料在不同Pass必须重新观察，分配不同s句柄，e/c及真实UUID稳定。候选明确绑定passId，验证和纠错保留候选专属来源及事实核对门禁。State1/2/3保持严格旧读取；重启不续跑旧Checkpoint。

新增 `review_passes`，显式迁移 `0008_review_passes`。六维固定顺序、selected/ordinal和READY/RUNNING/COMPLETED/INCOMPLETE/SKIPPED生命周期由平台持久保存；Pass事实与REVIEW_PASS_UPDATED序号事件同SQLite事务，提交失确认精确读取，不从模型文字猜进度。Usage按实际ModelCall投影，未知响应与超窗真实用量不能回填正常数字。恢复时运行Pass中断，未启动Pass明确跳过，不重演收费调用。

MultipassExecutor串行复用既有自主调查、工具、Evidence、验证、两次纠错及压缩逻辑。每Pass300、Run2000、去重10调用与各期限由费用账本、State及执行器共同约束；所有调用目的计入对应费用与次数。工具累计不新增上限。模型响应每批8工具、集合/字节/取消/资源保护保留；300/2000是上限，不保证存储等其他有限预算能支持用完全部调用。

普通调查预算耗尽可交付INCOMPLETE，零可检查材料不伪造checkedScope或FULL；总预算耗尽使剩余所选Pass按RUN_BUDGET_EXHAUSTED跳过。资源未释放、费用远端UNKNOWN、存储/完整性失败停止全Run，不能继续下一个Pass。取消时真实IO仍须确认退出，PREPARED退款、未知发送保留预留。自查红测补了首次发送前总期限耗尽：已启动Pass不完整，其余跳过，零HTTP、零虚假范围；阶段开始前总预算失败也收敛到未启动Pass的跳过事实。

去重先确定性召回同源/物理范围重叠且规则一致的疑似组，最多10调用有界比较根因/触发/修复。不同触发/修复保留，模型不确定或合法结构纠错耗尽保留原Finding并记录可能重复；未知/存储失败仍阻断。模型不能提升等级、产生新证据或改写Finding。合并保留贡献Pass/candidate/evidence/code/validation调用，primary按固定Pass顺序再candidateId取；Evidence128/候选32为全Run共享，未乘六。

Result3完整严格读取并确定性渲染；六Pass结果、选择、总体覆盖、谱系、实际用量与管理费用摘要可核查。最终原始字节/hash、SUCCEEDED与终态事件同事务；1MiB边界不扩大。Java按版本协商1.1，校验认证价格/授权后启动，严格导入原始Result3与Pass事件，保留身份/hash/引用/时间/计划/进度一致性及幂等原文。旧1.0/Result1/2路径保持。认证Evidence材料通过Java代理读取，前端展示只读草稿、所选维度、逐Pass状态/跳过原因/有限范围/原始贡献等级及历史。

配置REAL且正式价格、计量、独立Checkpoint、恢复及所有就绪门禁通过后才可声明1.1就绪；默认模拟仍旧版本，新多Pass模拟未作为产品能力声明。用户通过Java启动API显式提交ReviewPlan和费用授权；前端本切片提供只读投影，人工修订/批准/Handoff/Coding属于6B/6C。

## 合同与复现入口

新增九份独立Schema：State4、ContextManifest1.3、PassFact/Progress、HTTP1.1 Status/Event、局部调查/验证v2及去重决定。导出：服务目录 `python -m tests.export_multipass_runtime_contracts`。历史Schema/fixtures不改。生产回归入口 `tests/integration/test_multipass_runtime.py`，使用真实临时SQLite、Checkpointer、HTTP入口和DeepSeekAdapter MockTransport；完整六Pass、工具续接、候选验证与去重成功是离线模拟证据。

Java入口 `ReviewMultipassRuntimeTest`（导入、进度一致性与幂等读取）；前端 `ReviewDraftPanel.test.tsx`（Result3的选择/不完整/历史只读显示）。完整命令、UTC起止、退出码及日志根：`C:/Users/ROG/AppData/Local/Temp/code-weave-b6a3-f9h9lgo3`。最终结果在下方追加；初轮301通过/2失败为已修正压缩兼容边界的历史记录，不当最终结果。

## 保留限制

本轮未调用真实模型，不证明六维模型质量、语义去重正确或同模型独立验证不存在共同盲点。历史public描述偏差、256K真实高容量/POSIX未验、超窗恢复DISABLED保留。工程余量不保证Token估计零误差，管理费用非Provider账单/绝对远端财务封顶；官方价格须操作员重新核实，不使用fixture当真实核价。

本轮受影响矩阵不替代最终整批全量/干净Python3.12/Java及前端Legacy/真实验收。资源未确认不会安全终结；期限内残留任务或远端未知可使Run失败并保留保护，不能为报告成功掩盖未知。图与材料仍受有限Checkpoint/Artifact字节和集合约束；超过边界明确停止，不静默扩大。尚未实施6B/6C。

请设计窗口对抗式复审跨Pass消息/来源隔离、总/Pass预算先到、未知残留禁止后Pass、去重误合并、贡献谱系、Result原子交付、进度及Java严格导入。复审后另授权提交；不自动推进下一切片。


## 最终新鲜验收

| 验收 | 结果 | 起止时间及记录 |
| --- | --- | --- |
| Python受影响16模块 | 313通过，505.77秒 | UTC 2026-10-13T14:42:42.651783+00:00—2026-10-13T14:51:12.524748+00:00；python-affected.json/log |
| 最终多Pass/压缩专项 | 54通过，142.26秒 | final-runtime.log/json；在总期限红绿补修后运行；与313重叠不累加 |
| Java Review专项 | 40通过，0失败/错误/跳过；Maven1分51秒 | UTC 2026-10-13T14:39:27.954492+00:00—2026-10-13T14:41:21.957411+00:00；java-review.json/log；随后Java实现未改 |
| 前端正式测试 | 12文件、58项通过 | UTC 2026-10-13T14:42:42.651783+00:00—2026-10-13T14:42:54.151068+00:00；ui-test.json/log |
| 前端正式构建 | 通过；保留大于500KB分块提示 | UTC 2026-10-13T14:42:54.152616+00:00—2026-10-13T14:42:56.367935+00:00；ui-build.json/log |
| Schema | 54元校验、258本地引用通过 | schema.json；历史Schema/fixtures未改 |
| Ruff/格式 | 通过；144文件 | ruff/format.json/log；以实际format.log计数为准 |
| 差异/基线 | 通过；2abdd1d；暂存区空 | git diff --check / git rev-parse / git diff --cached |
| Python全量/干净3.12、Java/前端Legacy、真实模型、POSIX | 本轮未验 | 最终整批验收仍须安排 |

初轮303项有2个失败，源于Compactor新增State4集合压缩读取stateVersion未兼容既有软水位测试的简化对象，已改为版本判别安全读取；其301通过不作为当前结果。最终矩阵收集313项；矩阵开始后又增加了总期限红测并调整MultipassExecutor，故最后按影响重跑该执行器/压缩两个模块54项，未以较早矩阵替代新改动验证。

服务目录命令：
```powershell
.\.venv\Scripts\python.exe -m pytest tests/integration/test_multipass_runtime.py tests/integration/test_local_handles.py tests/integration/test_real_review.py tests/integration/test_fee_ledger.py tests/integration/test_model_calls.py tests/integration/test_real_context.py tests/integration/test_compaction.py tests/integration/test_compatible_token_budget.py tests/integration/test_real_schema_fixtures.py tests/integration/test_multipass_contracts.py tests/integration/test_schema_readiness.py tests/integration/test_contracts.py tests/integration/test_real_enablement.py tests/integration/test_workspace_recovery.py tests/integration/test_lifecycle.py tests/integration/test_store.py -q --tb=short --basetemp C:/Users/ROG/AppData/Local/Temp/code-weave-b6a3-f9h9lgo3/pytest
.\.venv\Scripts\python.exe -m pytest tests/integration/test_multipass_runtime.py tests/integration/test_compaction.py -q --tb=short
.\.venv\Scripts\python.exe -m ruff check .
.\.venv\Scripts\python.exe -m ruff format --check .
```
Java：services/control-plane目录 `../../mvnw.cmd -Dtest=Review*Test test`。前端：apps/web-console目录 `npm test` / `npm run build`。保存本次实际执行日志，无API付费或真实Coding。状态：开发离线交付，待设计窗口独立复审；未提交/推送，不推进6B。


## 复审55 · B6A3-R01最小补修（待独立复审）

生产HTTP红测在PREPARED/预留确认后推进账本时钟到Run开始+10800秒，原实现复现FAILED/PROVIDER_CALL_NOT_DISPATCHED；该红测有真实预留事实，不替代前述prepare前过期测试。

FeeLedger.close仅为可信预算错误保留原停止原因（Pass/Run调用、期限和总费用预算）；PREPARED的退款事务/失确认/不可重发保持。MultipassExecutor只有在预算白名单内且该停止原因已经写入当前Outcome.incompleteReasons时才按不完整收尾，剩余所选Pass按RUN_BUDGET_EXHAUSTED跳过。通用未发送错误仍致命，不忽略任意stopReason；DISPATCHING仍UNKNOWN保留预留。

正式回归覆盖prepare前过期、预留确认后dispatch前过期、已dispatch取消UNKNOWN，以及非预算PRICE_PROFILE_CHANGED反例。HTTP零发送、当前Pass空范围/INCOMPLETE、费用预留和消耗/调用次数零、LOCAL_CLOSED/REFUNDED、后续所选跳过、六维事实与Result3一致均已确认。调用已进入DISPATCHING不能因零HTTP观察就退款。新增测试产物仅写临时测试目录，原不可变历史不改。

新鲜受影响费用/调用/多Pass三模块63通过（148.59秒；UTC 2026-10-13T15:03:44.872997+00:00—2026-10-13T15:06:15.642014+00:00），Ruff和144文件格式通过。保存新输出后的期限单例又重跑1通过（2.98秒，与63重叠不累加）。原独立探针1通过（3.86秒）。Java临时离线探针以本次生产链路Result原字节及最终六Pass事实调用ReviewResultV3.validate、ReviewPassProgress.validate/matchesResult，通过；UTC 2026-10-13T15:07:18.204968+00:00—2026-10-13T15:07:19.683968+00:00，原字节hash及完整代码/编译运行命令见java-probe.log/json与DeadlineProtocolProbe.java。该探针证明Java严格读端和进度匹配接受本次结果，未冒称新鲜重跑产品导入全矩阵或前端。

补修范围：费用关闭分类、多Pass停止原因核对及正式Python回归/交付记录。Java/前端/Schema无实现变更，原独立40/58与Schema54/258证据保持为补修前证据；本次未重跑313大矩阵、全量/干净环境、Legacy、付费或真实多Pass。日志根C:/Users/ROG/AppData/Local/Temp/code-weave-b6a3-r01-sjjqifb_。首次构建临时Java探针的Maven属性未加引号导致PowerShell解析错误，修正引号后构建/探针通过，无产品故障遗留。

复现：服务目录 `.venv/Scripts/python.exe -m pytest tests/integration/test_fee_ledger.py tests/integration/test_model_calls.py tests/integration/test_multipass_runtime.py -q --tb=short`；精确边界用 `-k run_deadline_before_dispatch`。保持未提交/未推送/未付费，不进入6B。
