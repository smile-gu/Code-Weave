# 批次 6 最终离线统一验收

最新状态：62复核后最小测试补修及两套625全量通过；独立复审待定，详细见文末追加章节。以下原样基线失败记录保留。

固定产品基线：`4da995a59f93c637600cddc82b10f878d37734ab`。本轮阶段一已经执行，结论为 **未通过**：两套 Python 全量均有19失败，不能宣布批次6最终验收完成，不进入真实阶段。

权威范围：仓库外61最终清单、51冻结稿。产品实现/测试未改；工作区仅此验收记录和README明确过时说明修正，均未暂存/提交/推送。零付费、零真实CC，不读取历史API凭据，不进入批次7。

证据目录：`C:/Users/ROG/AppData/Local/Temp/code-weave-b6-final-i9luohtw`。每项JSON记录准确命令/cwd/UTC起止/退出码，日志和本轮XML均保存；Java正式XML在Legacy clean前复制，禁止按现有target混计。

## 统一结果

|验收|本轮结果|适用范围|
|---|---|---|
|当前Python3.12全量|625收集，606通过、19失败、0跳过；831.51秒；退出1|python-full.xml/log；新鲜固定基线失败不能用老专项覆盖|
|干净Python3.12官方脚本|锁安装/editable/pip check通过；全量606通过、19失败，831.79秒；退出1|官方脚本在pytest处停止，后续迁移/启动/Ruff尚未由该脚本执行|
|另行干净启动探针|独立新venv锁安装、pip check、空库0008、真实Uvicorn ASGI服务、401无认证/认证READY、正常lifespan退出通过|clean-smoke.json与五段日志；只运行SIMULATED空环境，未运行官方main入口，不代替官方全脚本通过|
|Java正式全量|343通过、0失败/错误/跳过；clean test|独立java-formal-xml；6:59；真实临时产品存储、外部模型/Coding用替身|
|Java Legacy|39通过、0失败/错误/跳过；profile clean test|独立java-legacy-xml；29.740秒，与正式计数分开|
|正式前端|68通过、14文件；生产build通过|两段日志；已有>500KB chunk提示保留|
|Legacy前端|3通过、1文件；类型build通过|test:legacy-worktree-run / build:legacy-worktree-run|
|Schema|68份元校验、331本地/跨文件引用解析，32 Typed导出完全一致、23组新版本正反例通过|schema_check.json；不运行会覆写合同的导出入口。其他旧fixture反例由本轮Python/Java合同测试覆盖；失败路径明确列下|
|静态|Ruff通过、144文件格式通过；显式Review受管Java清单格式检查与差异检查通过|不对output或目录级全量执行formatter|
|不可变/无关产物|验收前所记output/旧可视化文件SHA全部保持|preserved-files.json；只读比对，不宣称恢复修改时间|

## 命令与UTC时间

完整命令/cwd见各JSON；以下均为新鲜，不是历史证据。

|项|UTC起止|退出码|证据|
|---|---|---|---|
| python-full | `2026-10-14T10:01:26.435766+00:00` → `2026-10-14T10:15:23.288023+00:00` | 1 | python-full.log / python-full.json |
| python-clean | `2026-10-14T10:01:36.573404+00:00` → `2026-10-14T10:17:13.603544+00:00` | 1 | python-clean.log / python-clean.json |
| ruff | `2026-10-14T10:15:23.290107+00:00` → `2026-10-14T10:15:23.567170+00:00` | 0 | ruff.log / ruff.json |
| ruff-format | `2026-10-14T10:15:23.568680+00:00` → `2026-10-14T10:15:23.717103+00:00` | 0 | ruff-format.log / ruff-format.json |
| java-formal | `2026-10-14T10:01:46.763728+00:00` → `2026-10-14T10:08:48.066880+00:00` | 0 | java-formal.log / java-formal.json |
| java-legacy | `2026-10-14T10:08:48.227763+00:00` → `2026-10-14T10:09:19.788289+00:00` | 0 | java-legacy.log / java-legacy.json |
| ui-formal-test | `2026-10-14T10:01:57.333927+00:00` → `2026-10-14T10:02:06.677918+00:00` | 0 | ui-formal-test.log / ui-formal-test.json |
| ui-formal-build | `2026-10-14T10:02:06.678966+00:00` → `2026-10-14T10:02:08.827384+00:00` | 0 | ui-formal-build.log / ui-formal-build.json |
| ui-legacy-test | `2026-10-14T10:02:08.828242+00:00` → `2026-10-14T10:02:12.909025+00:00` | 0 | ui-legacy-test.log / ui-legacy-test.json |
| ui-legacy-build | `2026-10-14T10:02:12.910667+00:00` → `2026-10-14T10:02:13.802987+00:00` | 0 | ui-legacy-build.log / ui-legacy-build.json |
| chain-python | `2026-10-14T10:05:55.381714+00:00` → `2026-10-14T10:06:40.957911+00:00` | 0 | chain-python.log / chain-python.json |
| chain-java | `2026-10-14T10:09:00.441274+00:00` → `2026-10-14T10:09:13.395081+00:00` | 0 | chain-java.log / chain-java.json |

## Python阻塞：最小定位证据

**B6-FINAL-R01（回归装配，待独立复核）**：18个历史State2协议测试在lifespan初始化访问`tests/legacy_runtime_fixture.py:20`的`supervisor.executor.engine`。6A3正式bootstrap已包装为`VersionedReviewExecutor`，有`single/multipass`，没有`engine`。错误发生于目标调用之前，不能据此证明18条业务门禁通过或失败。建议最小修正测试替身装配，在既有单Pass执行器上明确选择历史State2，不给生产执行器添加仅为测试便利的属性；需先独立批准补修后重跑，不在此次固定基线验收中悄改。

**B6-FINAL-R02（旧迁移断言，待独立复核）**：`test_empty_database_upgrade_enforces_constraints`仍要求7张产品表，当前`0008_review_passes`确有新增`run_fee_budget/model_call_reservations/review_passes`。实际空库迁移/Readiness正常，测试失败是精确表清单不同；仍须正式同步当前迁移验收断言，不能以独立探针取代失败结果。

19条失败完整名单：

- `tests.integration.test_evidence_protocol::test_legacy_graph_corrects_semantic_references_without_partial_evidence[line]`
- `tests.integration.test_evidence_protocol::test_legacy_graph_corrects_semantic_references_without_partial_evidence[hash]`
- `tests.integration.test_evidence_protocol::test_legacy_graph_corrects_semantic_references_without_partial_evidence[coverage]`
- `tests.integration.test_evidence_protocol::test_legacy_graph_corrects_semantic_references_without_partial_evidence[candidate]`
- `tests.integration.test_evidence_protocol::test_legacy_graph_corrects_semantic_references_without_partial_evidence[schema]`
- `tests.integration.test_evidence_protocol::test_legacy_graph_corrects_semantic_references_without_partial_evidence[schema-validation]`
- `tests.integration.test_evidence_protocol::test_legacy_graph_corrects_semantic_references_without_partial_evidence[schema-large]`
- `tests.integration.test_evidence_protocol::test_legacy_graph_corrects_semantic_references_without_partial_evidence[always-bad]`
- `tests.integration.test_evidence_protocol::test_legacy_graph_corrects_semantic_references_without_partial_evidence[snapshot]`
- `tests.integration.test_evidence_protocol::test_legacy_graph_corrects_semantic_references_without_partial_evidence[storage]`
- `tests.integration.test_evidence_protocol::test_legacy_graph_corrects_semantic_references_without_partial_evidence[diagnostic]`
- `tests.integration.test_evidence_protocol::test_legacy_graph_corrects_semantic_references_without_partial_evidence[cancel]`
- `tests.integration.test_evidence_protocol::test_legacy_model_uses_deleted_diff_page_projection_through_atomic_result`
- `tests.integration.test_migration::test_empty_database_upgrade_enforces_constraints`
- `tests.integration.test_submission_feedback::test_legacy_schema_feedback_explains_constraints_without_rejected_values[missing]`
- `tests.integration.test_submission_feedback::test_legacy_schema_feedback_explains_constraints_without_rejected_values[empty]`
- `tests.integration.test_submission_feedback::test_legacy_schema_feedback_explains_constraints_without_rejected_values[uuid]`
- `tests.integration.test_submission_feedback::test_legacy_schema_feedback_explains_constraints_without_rejected_values[extra]`
- `tests.integration.test_submission_feedback::test_legacy_schema_feedback_explains_constraints_without_rejected_values[many]`

干净环境稳定复现相同19项，因此不是仅当前venv偶发。产品缺陷尚未由这些失败证明；旧门禁覆盖空洞及全量无法绿均为最终验收阻塞。未补修产品/测试，不通过删例或改输入消除冲突。

## 离线跨层链路

真正使用Python六Pass正式执行器、Context/Artifact/Evidence/费用产品SQLite，Adapter仅HTTPX MockTransport（假密钥offline-only）；运行既有 `test_six_passes_use_fresh_views_and_real_fee_mock_transport[True]`，1通过42.65秒，六维COMPLETED、1最终Finding/六贡献、去重1调用、费用管理事实，Result原子保存/资源RELEASED/事件序号。仓库外pytest hook在断言通过后读取持久原request/status/events/result/hash，未改身份或重渲染结果。

这些原字节再进入独立Java临时环境`OfflineChain`：真实ReviewExecution、ReviewFactImporter逐事件双重幂等/finish双重幂等、ReviewProtocol校验、报告创建→人工修订（3000中文+1000四字节字符）→批准→准备交接→历史读取→显式启动原CodingTurnApplicationService，队列替身拒绝、Orchestrator/Readiness/Git guard替身且workspaceCheck空实现，真实FileTurn存储。断言prepare/GET零Turn/零队列；最终只一个FAILED/TURN_QUEUE_REJECTED且sourceHandoffId准确，重试/恢复不排第二Turn，原Result字节始终不变。chain-java退出0。

这证明一个跨语言**离线原字节接力闭环**；不是在线Java↔Python HTTP单链实测，网络认证/漂移另由正式测试证明，不能声称该接力覆盖真实工作区/网络/CC修复质量。

## 浏览器及交互

正式生产dist、真实Chromium/Playwright，只连接隔离临时Python Mock API，材料来自上述Java原输出；没有真实服务或Coding入口可达。浏览器验证3000中文+1000四字节正文、历史只读、400确定拒绝后仍可编辑、503未知请求刷新无自动发送/保存按钮禁用、显式重试请求全文/requestId完全相同。browser-requests.jsonl断言三次用户PUT、0 start/0 Coding；截图及snapshot在.playwright-cli，browser-verification.json保存覆盖范围。

有限覆盖确认、零Finding关闭、准备/批准不启动、UNKNOWN恢复等由本轮正式组件/Java测试断言：ReviewReportEditor.test.tsx `saves a version with original grade and requires explicit approved selection and incomplete acknowledgement`；ReviewReportTest `emptyFindingReportsCanBeClosedButCannotCreateAnEmptyApproval`；ReviewHandoffPanel恢复/只读/不自动发送；ReviewHandoffTest跨报告两个顺序、无Turn重启零发送/有Turn重启零排队。未在本轮浏览器逐项操作有限覆盖/零Finding，不冒称全浏览器验收完成。

工具失败及验收替身错误保留：cua首次初始化本地资源路径缺失，转Playwright；初次mock changes字段不完整造成Diff组件报错，修正仓库外Mock响应后重启再验证；没有修改产品或降低门禁。Playwright初次无效页面路由/过时ref及命令引号错误只属于自动化操作失败，最终语义断言通过，日志仍保存。

## 可追溯安全与旧读回

本轮通过测试中：6A2 `test_exit_after_confirmed_prepare_refunds_without_restart`、`test_failed_recovery_must_not_advertise_fee_ready`；6A3 `test_expired_run_before_first_send_delivers_incomplete_without_fake_scope`；6B `legalSourceTextSurvivesCreationAndEditing`；6C `anotherOpenReportRejectsBeforeIntentAndCanCloseBeforeSameHandoffStarts`两扫描顺序、`absentTurnAfterLostSubmissionRetainsIntentBlocksEditsAndOnlyExplicitRetryDispatches`、`publishedTurnIsReconciledAtRestartWithoutQueuingAgain`、unknown资源/孤儿/并发/HTTP身份反例。旧State1/2/3/4及Result1/2/3/Head1/2/无来源Turn合同读回由对应合同/Checkpoint/文件测试覆盖，18条旧State2执行协议因装配故障不能称已覆盖。

`test_incomplete_empty_schema_cannot_be_ready_or_accept_runs`缺表/列 NOT_READY；费用有数据迁移与禁止破坏降级测试通过。未另造全套有数据逐版本迁移矩阵，不能宣称已证明全部历史数据库演进组合。累计工具数无门禁，单响应/预算/取消门禁保持；旧工具>64/80续接测试见本轮通过用例。取消真实IO未退/UNCERTAIN、意图失确认零发送、去重不升级、漂移filter/index由现有正式专项覆盖。

## 文档变更及停止标准

README明确修正当前批次6、10张产品表/0008和Result3，保留旧按时间章节为历史，旧27648/32768属于历史v1，当前v2默认256K内容未变。无产品策略重构。

下一步交设计窗口核对这两项阻塞及证据后决定最小测试装配/迁移断言补修。两套全量未通过，阶段一未完成；真实六Pass模型及真实CC阶段未授权/未执行。历史public描述偏差、同模型语义局限、256K未真实高容量、超窗恢复DISABLED、POSIX未验继续保留；本轮Mock成功不消除模型质量或注入风险。真实阶段需要另定样例/请求数/费用/修改范围，不自动付费试到成功。


## 62复核后的测试维护补修与重新验收

本节是后续新鲜结果；前文4da995a原样606通过/19失败以及官方脚本中断证据全部保留。**开发验收中这两项测试维护阻塞已消除，待设计窗口独立复审**。不能把本轮结果称作4da995a原样通过：产品仍为4da995a，另有未提交的两个测试文件及README/本验收文档差异。

最小改动：tests/legacy_runtime_fixture.py明确assert版本分发器类型、single/multi独立engine及multi State4不变，仅将single.engine设为历史State2，保留18条全部业务测试和生产分发器；注释同步。tests/integration/test_migration.py精确加入三张当前产品表，增加0008版本断言，保留Readiness/writable/WAL、外键拒绝、缺Schema断言。无生产engine兼容属性、无删除/跳过/改输入或放宽业务门禁。

原19失败及相关旧协议专项：`tests/integration/test_evidence_protocol.py tests/integration/test_submission_feedback.py tests/integration/test_migration.py`，32通过109.62秒；日志C:/Users/ROG/AppData/Local/Temp/b6-final-testfix-special.log。

本轮证据目录：C:/Users/ROG/AppData/Local/Temp/code-weave-b6-testfix-lo1msbxc。

|项|结果|
|---|---|
|当前Python3.12全量|625收集/625通过/0失败/0错误/0跳过，935.03秒；python-full.xml及日志|
|官方干净Python3.12完整脚本|锁安装/editable/pip check→全量625通过931.95秒→Ruff/144格式→空库迁移→官方main服务启动/认证READY→自启动子进程终止并wait→临时目录清理，脚本退出0且CLEAN_ACCEPTANCE标记；python-clean.log。脚本只确认其实现的terminate/wait，不额外声称覆盖所有操作系统优雅停机行为|
|当前静态|Ruff通过、144文件格式检查通过；git diff --check通过|
|Java/UI/Schema/浏览器接力|产品未改，本轮未重复；前轮4da995a Java343/Legacy39/UI68/Legacy3与构建、Schema68等证据仍按前文适用范围保留。浏览器Mock与非在线HTTP原字节接力边界不改变|
|历史保护|output/旧可视化验收前SHA再次全部匹配；原失败日志/XML、真实Result等无修改|

UTC起止、命令及cwd见对应JSON：

|项|UTC起止|退出码|证据|
|---|---|---|---|
|python-full|`2026-10-14T10:37:08.721483+00:00` → `2026-10-14T10:52:46.343747+00:00`|0|python-full.log / python-full.json|
|python-clean|`2026-10-14T10:37:18.864822+00:00` → `2026-10-14T10:54:13.218308+00:00`|0|python-clean.log / python-clean.json|
|ruff|`2026-10-14T10:52:46.344757+00:00` → `2026-10-14T10:52:46.507988+00:00`|0|ruff.log / ruff.json|
|ruff-format|`2026-10-14T10:52:46.508998+00:00` → `2026-10-14T10:52:46.638468+00:00`|0|ruff-format.log / ruff-format.json|

结论：批准范围内两个测试文件补修及重新验收通过，未出现新的产品失败。B6-FINAL-R01/R02由设计窗口复审关闭；整批真实六Pass/真实CC质量、高容量/POSIX及浏览器未实测项仍待验，不宣称完整真实验收通过。零付费/零真实CC、未暂存/提交/推送，不进入下一批。仅在独立复审后另获授权提交测试及说明。
