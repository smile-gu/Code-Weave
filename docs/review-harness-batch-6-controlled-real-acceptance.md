# 批次6受控真实审查：一次失败并停止

产品 `07649dc230bae2089b5043232a6cc691a70136fb`，冻结稿64。用户授权一个官方 deepseek-flash 六Pass Run、管理预算¥3、所有purpose合计最多60次尝试，失败不自动重跑。**真实审查未通过**；零真实CC、未改生产实现、未提交/推送，不进入下一批。

## 准备与装配边界

执行进程起初无环境密钥；用户随后明确“我们就用之前的密钥”，据新授权只在执行进程环境使用此前提供的密钥，结束清除；未扫描历史文件找凭据、未写进验收脚本或产物。不输出密钥/Authorization。

公开联网核实官方价格 https://api-docs.deepseek.com/zh-cn/quick_start/pricing/ ，采用保守高峰未命中输入2元/百万Token、输出8元/百万Token，不依赖时段/缓存折扣。HTML摘要 `sha256:5a7b1832592387340f2fc456399b34b89b05f3fa167c2e35909e2fa4afe021e3`；核实UTC `2026-10-14T11:41:49.803740Z`，到期 `17:41:49.803740Z`。Profile `official-flash-20261014-controlled-b6`，摘要 `sha256:bf35218b9d6762c8762d643ebd6f01bd9d22e82027d127d8170d10f9983851c8`。产品认证价格接口读回、摘要核对及Java ReviewPriceProfile严格校验通过；未覆盖Java在线价格HTTP入口。

仓库外AttemptGate在唯一httpx transport出口串行锁、SQLite BEGIN IMMEDIATE及FULL同步持久次数，发送前提交并重读确认；未知尝试不退款，无HTTP自动重试。离线70个并发提交只有60到下游、后10阻断，五purpose共用，首个未知仍计数。计数失败不发送，崩溃可保守多计；不是产品新功能。正式300/2000及费用、引用、取消门禁不改。

只将baseline/src复制到新临时Git仓库，提交基线后覆盖candidate/src为未提交改动，oracle/答案/README不进Snapshot。Java生产SnapshotCapture采集固定输入。baseline6通过/candidate仅nullable-null失败属设计窗口准备证据，本轮未重跑oracle。

本轮采用**Python正式认证HTTP Run入口（隔离ASGI客户端）+Java生产Snapshot及结果读回**，不覆盖Java在线Review启动API。隔离Mock预跑六Pass成功12次、空Finding，仅证明协议/账本/读回；Java原request/result字节、事件/finish幂等和价格摘要校验通过。Java临时验收使用前轮保存的4da995a生产类；该提交到07649dc仅测试/文档变动，生产类适用边界如实保留。初次Mock同步迁移在async中报错、零发送，仓库外脚本改为to_thread后另mock2通过；日志保留，真实Run只有一个。

## 样例与身份

- 证据根：`C:/Users/ROG/AppData/Local/Temp/code-weave-b6-real-hwtnp8pa`。
- Git基线：`9cef7bdc6c10880036db73912003da23c22b160b`。
- 当前指纹：`sha256:4ffcbfb583aed755c60c5e224fd0ee41b6a3c368ed5c9028970497981b8526da`。
- Session：`3baf6bfb-21f6-4c37-94f4-2d25779396ab`。
- Thread：`e7777089-5cae-4dbe-ba24-41d25ee7e7e0`。
- Run：`05c60930-ee73-4b09-9c29-76cb51cde564`。
- Snapshot：`3ae808d8-a0bd-4794-b220-1f142d18d2f0`。
- Manifest：`sha256:0eb71f4e59d7ab1cc35dec7f3f4e1167ed09b65cbc3bc040d5e09b6fe2c753ed`。

用户输入严格按64固定文本，未提示缺陷位置/期望Finding。完整请求及Profile在证据根，原始产物不改。

## 真实结果与费用

UTC创建 `2026-10-14T11:44:55.725960Z`，终结 `11:44:58.064478Z`。实际尝试 **1/60**，唯一调用 correctness/INVESTIGATION，modelCallId `4077a809-181b-468f-91ac-7829762e8fd6`。

Run `FAILED / REVIEW_EXECUTION_FAILED`，资源 `RELEASED`。correctness INCOMPLETE，其余五Pass SKIPPED/REVIEW_RUN_INTERRUPTED；没有Result、Report或Handoff，未补齐成功、未再次真实请求。

有效Usage：输入1194（缓存命中768/未命中426）、输出77、合计1271。保守管理账本支出 **3004微元=¥0.003004**，预留0、overrun0、调用LOCAL_CLOSED、remoteOutcomeUnknown=false。此为管理支出，非控制台实际账单；余额不构成自动重试许可。

首个模型回复请求git_diff({})和read_file(src/main/java/acceptance/TextLengths.java)，已保存Checkpoint revision3。正文/思考不导出至交付文档。未进入候选/Evidence独立验证，不能判定模型对null回归/正确反例的审查能力。

## B6-REAL-R01：多Pass Diff来源分页重建使用旧Registry

失败后仅使用MockTransport重放已保存工具名/参数，隔离的新mock-replay数据库稳定复现，零付费。工具异常栈捕获在仓库外wrapper，不修改产品/旧产物。

确切路径：runtime/review.py `_tools` → application/handle_observations.py `observe_tool` → `observe_page:33`。代码通过 `HandleRegistry.model_validate({...registry.model_dump(), sources:rows})` 重建，而实际State4是MultipassRegistry。旧基类拒绝 `sources[].passId / activePass`，产生extra_forbidden ValidationError，Supervisor转为通用执行失败。调用Usage/费用已结算、工具调用已保存，因此不是预算不足或模型验证回复错误。

最小修复方向交设计窗口决定：保留具体Registry类型与多Pass身份，不能删除passId/activePass或宽松接收额外字段；补State3/4真实Diff分页、跨页/半行/多文件及权限身份回归，并检查其他Registry重建入口。既有六PassMock只read_file，没有覆盖该Diff分页路径；625离线通过不能排除本缺口。本轮不实施生产补修、不再付费试错。

## 证据与停止

价格：official-pricing.html、price-profile.json；身份：identity.json；门禁：attempt_gate.py、preflight_gate.py、gate-preflight.log；Mock：mock2/、mock-java-import.log；真实：real/runs.sqlite、checkpoints.sqlite、attempts.sqlite、request/status/events、calls-summary/fees-summary及real-run.log；离线重现：replay_tools.py、offline-tools-trace.txt、offline-replay.log、mock-replay/。

真实Result及Java真实结果导入、人机修订/批准/准备均未到达，真实CC仍未授权。先交设计窗口复核B6-REAL-R01，再决定最小补修和另行真实测试授权。原失败件保留，不替换、不重跑至成功。历史public偏差、同模型语义局限、256K高容量/POSIX未验、超窗恢复DISABLED继续披露。


## 65冻结后的B6-REAL-R01离线补修

基线07649dc，未提交产品差异。真实1次失败、原始Checkpoint/费用¥0.003004保留，新真实Run未授权/未执行。开发补修与离线验收完成，待设计窗口独立复审关闭问题，不宣称真实模型闭环已成功。

生产仅改application/handle_observations.py：`HandleRegistry.model_validate`改为`type(registry).model_validate`，删除不再使用的基类导入。仍严格校验来源、passId/activePass、坐标、游标连续性；未删字段、extra=ignore、Prompt绕行、禁用Diff或新增版本工厂。其他显式Registry重建入口静态核对使用具体类型，不能据检索宣称所有工具组合已覆盖。

新增tests/integration/test_diff_registry.py共8项：原红测State3通过/State4因旧类extra_forbidden失败（1红1绿）；修复后具体类型与身份/观察范围断言、State3/4半行连续完成/跳页拒绝4项通过。真实双文件Git Diff由独立临时仓库产生，准确发布到新Snapshot，生产RealReviewExecutor._tools→ToolGateway.git_diff→observe_tool/observe_page→Checkpoint保存/精确读回→DeepSeekAdapter下一请求wire，连续多页与半行、多文件裁剪均覆盖；所有授权物理行必须代码行，不能文件/hunk头。State4切换第二Pass同源新句柄、旧句柄提交Evidence拒绝SOURCE_NOT_OBSERVED_IN_PASS；原UUID/权限门禁不改。此测试固定合法工具调用，不发送Provider；不能当模型自主选择工具质量证明。

新测试装配的中间错误（复用旧Snapshot留下多余文件、缺GraphMessage正文/provider映射、读取ModelRequest错误属性、缺limitations/提交函数参数）均仅改新增夹具，未弱化生产门禁；最终8项通过5.08秒。旧实时失败根因对应红测与离线真实工具重放原件保留。

证据目录C:/Users/ROG/AppData/Local/Temp/code-weave-b6-diff-fix-r8i6aotq：registry-red.log、new-tests.log、affected.log、replay-green.log、两套全量日志/XML/JSON、schema.log、historical-failure-hashes.json。新目录Mock重放原Checkpoint中git_diff/read_file工具名参数，六Pass SUCCEEDED/RELEASED，13个Mock响应、零付费；原失败目录未修改且全部SHA再核对一致。空Finding属于离线脚本结果，不声称模型真实找到缺陷。

|本轮验收|结果|
|---|---|
|受影响专项|101通过307.27秒；diff_registry/local_handles/multipass_runtime/tools/evidence_protocol及原仓库外test_page_registry.py；涵盖原独立2探针。与全量重叠，不相加|
|当前Python全量|633收集/633通过/0失败/错误/跳过，923.34秒；python-full.xml|
|官方干净Python3.12完整脚本|633通过917.73秒，锁安装/editable/pip check、Ruff/145格式、空库迁移/官方服务启动/认证Readiness及脚本子进程退出清理全走完，退出0/CLEAN_ACCEPTANCE；不超出脚本声称OS优雅停止范围|
|Schema|68份元校验/331引用/32Typed完全一致/23正反例组通过；未写Schema|
|静态|Ruff/145格式及git diff --check通过|
|历史/范围|原真实运行文件全部SHA保持；Java/UI/合同未改，前轮证据适用范围保留，本轮未重跑。真实模型/CC、POSIX/高容量仍待验|

完整命令/cwd/UTC起止存JSON。本次复用的仓库外runner元数据模板曾带旧SHA4da995a，实际HEAD一直为07649dc；现已显式更正JSON基线并保存original-runner-metadata原件及更正说明，不以旧SHA冒称当前补修。运行日志未改。

|项|UTC起止|退出码|证据|
|---|---|---|---|
| python-full | 2026-10-14T12:07:36.100803+00:00 → 2026-10-14T12:23:01.897689+00:00 | 0 | python-full.log / .json |
| python-clean | 2026-10-14T12:07:46.253880+00:00 → 2026-10-14T12:24:33.953873+00:00 | 0 | python-clean.log / .json |
| ruff | 2026-10-14T12:23:01.898693+00:00 → 2026-10-14T12:23:02.063825+00:00 | 0 | ruff.log / .json |
| ruff-format | 2026-10-14T12:23:02.064329+00:00 → 2026-10-14T12:23:02.192203+00:00 | 0 | ruff-format.log / .json |

剩余限制：具体类型保持和工具身份回归通过，不证明模型语义判断正确、六维真实质量或完整Java在线启动路径。原真实失败未关闭为成功，剩余59次/费用余额不自动授权新Run。待设计窗口独立复审后另授权小粒度提交/新的受控真实复验；未提交/推送/新增付费/真实CC/下一批。


## 2026-10-16 补修后单次真实复验（未通过）

记录时间：2026-10-16T16:34:46+08:00。基线 `30a0054f7ab3ec8ed865ce2ff0f18ccd25d7809a`。授权为一个新六 Pass Run、¥3、发送最多60次、失败不重跑；未运行真实CC、未提交或推送。

证据根：`C:/Users/ROG/AppData/Local/Temp/code-weave-b6-retest-_4vctj40`。Run：`aec9c177-2b77-451c-869c-c20d7550c264`。沿用64固定Snapshot字节与固定用户输入，新Run/Thread、独立数据库；未恢复旧失败Run。旧原件和费用保留。

### 预检和发送事实

官方价格来源 https://api-docs.deepseek.com/zh-cn/quick_start/pricing/ ，于2026-10-16T08:29:25.403393Z核实，资料SHA256 `5a7b1832592387340f2fc456399b34b89b05f3fa167c2e35909e2fa4afe021e3`。保守价格输入2元/百万、输出8元/百万；Profile `official-flash-20261016-b6-retest`，6小时有效。认证Readiness、Capabilities及账本预检通过。仓库外严格发送门禁预验70并发仅放行60次，未知尝试占次数、各purpose共用计数、发送前持久确认，HTTP retries=0；隔离Mock预跑12请求通过，仅证明装配协议。

真实入口为Python正式认证ASGI Run入口、Java已保存固定Snapshot，不覆盖Java在线Review启动API。本轮15/60请求，全部调用LOCAL_CLOSED、有有效Usage且remoteOutcomeUnknown=false。输入81135、输出14586、总95721 Token。保守管理支出¥0.278958，reservedMicros=0、overrunMicros=0；此非控制台实际账单。

### 结果、定位与质量

UTC 08:30:22创建、08:31:56终止。FAILED / PROVIDER_STRUCTURED_OUTPUT_INVALID，diagnosticId `b0fc9b3e-7cf6-4ed0-a2b3-8ebf07a10d8b`，资源RELEASED，无Result。

correctness COMPLETED（7调用）；consistency INCOMPLETE（8调用）；security/performance/architecture/testing SKIPPED。前一轮Registry异常本次未再出现，真实git_diff及read_file成功进入Evidence和验证，但不能据此声称全部分页边界真实覆盖。

最后Checkpoint保留consistency三份验证回复及两次JSON_INVALID反馈。对原文执行标准json.loads三份均报Extra data，位置分别2883/5144/5145（0基），长度5305/5146/5147；不是仅凭通用错误码猜测。证据 `real/offline-json-replay.json`。未自动修剪、多取JSON、放宽Schema或再次发送。这里只确认已保存正文非法；本轮未保存完整Provider原始信封，不能声称核对过finish_reason或传输层原始正文。

correctness账本已有2个Finding：HIGH null回归有源码、Diff和Evidence支持；LOW Finding却明确描述requiredLength为行为保持、无正确性影响的安全重构，并被验证为VERIFIED。正确反例未被说成null缺陷，但被纳入Finding，属于结果质量偏差，需设计复核；不能把Pass COMPLETED当作质量通过。consistency非法回复尚未通过Schema，不能当作已生效验证。

### 统一结论和未验

本次单次执行与停止保护已核对；六Pass闭环失败，无最终Result，Java严格导入/UI/Handoff在本轮未验。真实六Pass质量、最终去重汇总均未验。既往离线全量属于历史证据，本轮未重跑产品矩阵，未改产品代码。未进行故障补修、未付费重跑、未运行真实Coding。保持原件，由设计窗口决定JSON纠错与良性候选进入Finding的后续处理。


### 2026-10-16T17:08:06+08:00：R02/R03离线定位与最小补修交付

三次验证请求重建摘要及Token计量逐一匹配原记录；原反馈仅JSON_INVALID。补充有界结构错误位置、单对象纠错要求，以及调查/验证的Finding资格说明，仍用REJECTED路径。181项不重复受影响用例通过，另4项重复核对通过；Ruff/格式/差异检查通过。详见[离线补修记录](review-harness-batch-6-json-and-finding-quality-fix.md)。原15请求失败、费用和Ledger保持；本轮零付费、零CC、未提交推送。真实验收仍未通过，补修待设计独立复审。


### 2026-10-16T17:34:21+08:00：R02/R03补修后单次受控真实复验（失败停止）

Run `47145d56-1f5c-4341-8198-4bb4207cf255`，29/60请求，管理¥0.461080，全部Usage已知且SETTLED、预留0、FAILED/RELEASED、无Result。correctness/consistency/security/performance完成，architecture不完整，testing跳过。一次JSON语法错误收到具体位置和完整拒绝正文后，下一请求成功修正；良性c2/c5均REJECTED。实际停止是architecture验证用省略号拼接candidateText，FACT_QUOTE_INVALID；该Pass此前已用完两次Schema纠错额度。详见[本轮真实复验与离线定位](review-harness-batch-6-quality-real-retest.md)。未改产品、未重跑、未CC、未提交推送，真实验收仍未通过。


## 2026-10-16 POSITION_V1 单次真实验收

详见 `docs/review-harness-batch-6-position-real-acceptance.md`。新Run `0c84091a-8047-4f60-9c4e-500e7e26aa04`，33次，六Pass COMPLETED，SUCCEEDED/RELEASED，Result3.0；24条位置绑定回放一致。管理计费¥0.567602，全部SETTLED，无预留/超支/未知。null回归检出、良性重构REJECTED；最终同一null问题仍有5条跨Pass重复Finding，去重未召回，另有摘要行号偏差。保留全部原始证据，不据此批准整批。证据根 `C:\Users\ROG\AppData\Local\Temp\code-weave-b6-position-real-qmb0wiq8`。无CC、无重跑、无产品修改、未提交推送。


## 2026-10-16 基线6ef9a01：最小语义去重单次真实验收

一个新Run `187831ef-ae30-453c-9a01-81b8cd86a60e`；六Pass COMPLETED，SUCCEEDED/RELEASED，Result3.0。38次，Usage344723tokens，管理保守计费¥0.890296（不是实际账单），38份SETTLED，无预留/超支/未知。null回归检出、良性重构c2 REJECTED。9项原始判断形成1组/36条重叠边，但模型三次仅选择5条VERIFIED，排除4条RISK；首次额外字段被Schema拒绝，后两次整组句柄不匹配被拒绝。两次纠错耗尽后原样保留九项，未强并，没有接受的去重审计/合并。执行闭环和安全拒绝正确，真实合法整组决策与确认后合并未通过/未验；混合组分立本身符合冻结范围。详见 `docs/review-harness-batch-6-dedup-real-acceptance.md`。不追加付费、不改产品、不运行CC、不提交推送，交设计窗口复核。


## 2026-10-16T22:15:45.331511+08:00 兼容性预分区单次真实复验

HEAD6ef9a01＋已复审未提交补修；79产品文件及差异运行前后不漂移。唯一新Run `8640c4ef-6925-4e7f-adcd-04ad5c5434e4`，六Pass COMPLETED，SUCCEEDED/RELEASED，Result3.0；38次、327996tokens，管理保守计费¥0.850254（非实际账单），38份SETTLED，无预留/超支/未知。原始12项按键拆成5项RISK＋7项VERIFIED，完整分别交付。5项RISK在036获合法整组肯定，经SYSTEM审计与Checkpoint rev163确认后合并为一条，保留5贡献、9Evidence、1代码引用并集；真实合并成功路径已验但仅限该RISK组。另一组回复重复JSON键并漏c11，共享两次纠错耗尽后保留七项，最终8条，未强并。null回归检出、良性c2排除；c11架构Finding存在“相反null处理”及行号事实偏差待复核，报告质量不能判全通过。详见 `docs/review-harness-batch-6-dedup-partition-real-acceptance.md`。本轮不改产品或设计，无追加付费/CC、未提交推送，交设计窗口独立复核。
