# 批次 6C：不可变 Handoff 与显式 Coding 交付

基线：80ee607（6B）；冻结依据：仓库外 51 正式稿第7–9节及50确认记录。仅离线开发，不付费、不运行真实CC、不提交、不推送。

## 实现边界

- Java 受信来源投影：精确报告版本/摘要与批准选择、原Result身份/摘要、Snapshot/manifest、原Finding/Evidence、人工修订及用户要求形成Handoff1.0。原等级和假设保留；模型原文只是数据，不获得平台权限。不是自由Markdown旁路，无Python绝对存储路径。
- prepare独立显式动作，不启动Coding；列表/历史GET只读。内容先不可变发布，再将生命周期写入同一ReportHead。原Head v1严格读回；含handoffs的新Head使用独立v2 Schema，旧Schema不改。
- PREPARED/SUPERSEDED/REVOKED/STARTING/STARTED/START_BLOCKED与内容分离。新版本/重新批准替代旧未启动交接；关闭人工审阅撤销未启动交接；已启动不取消Turn。
- Session锁覆盖编辑/撤销/启动。STARTING未确认时禁止编辑、另一交接准备/启动、关闭与基线绕过；普通Coding受人工交互门禁约束，显式关闭人工审阅可返回Coding。Handoff不会解除Python资源保护。
- 启动核对当前批准/精确版本、Session、已可靠导入RELEASED、Coding空闲，以及原Snapshot分支/HEAD/活动基线/工作区指纹和全量Entry。复用Snapshot观察与ReadOnlyGit；两轮稳定观察，不重采Snapshot、不运行仓库filter、不修改index。IDE外部编辑仍非原子隔离。
- 固定turnId + requestId/digest可靠保存STARTING后，内部submitHandoff复用既有Coding队列和执行器。外部普通Turn API没有sourceHandoffId输入。Turn状态转换/持久恢复保持来源。丢响应按固定Turn精确重读；排队失败也关联同Turn，永不为同Handoff创建第二Turn。
- 重启只核实STARTING。存在精确Turn则确认STARTED，不重排队；明确不存在保留原意图，仅用户显式原请求可重试并重验门禁；读取未知保留保护。漂移START_BLOCKED，不能强制绕过。
- 前端展示交接原等级/修订/假设/历史，准备与启动分开；未知请求保留sessionStorage原内容，恢复不自动发送；存储丢失时可用后端保存的原启动身份显式核实。启动后刷新Turn查询。
- 原Result及历史字节不改；内容/Head仍为1MiB，开放Head保留1KiB关闭空间。交接历史不裁剪，不增加任意固定次数上限；容量不足拒绝或保留失确认保护。

## 本轮证据

命令均Windows/Java21/Node现有环境。原准备、启动、漂移、UI入口红测分别保存于 %TEMP%/b6c-prepare-red.log、b6c-start-red.log、b6c-drift-red.log、b6c-ui-red.log。

|验收|结果与范围|
|---|---|
|Java受影响矩阵|172通过，0失败/错误/跳过；服务目录 ../../mvnw.cmd '-Dtest=Review*Test,CodingTurn*Test,CodingSession*Test,FileCodingTurnRepositoryTest,SessionWorkspaceGuardTest' test；2026-10-14 17:09:22+08:00，2:12；%TEMP%/b6c-java-final.log。其后仅交接历史固定条数限制移除、可读性等局部调整另跑专项，不能冒称该矩阵在所有最终文本上重跑。|
|最终Handoff/Report专项|命令 ../../mvnw.cmd '-Dtest=ReviewHandoff*Test,ReviewReport*Test' test；准确最终结果见下方追加，%TEMP%/b6c-final-handoff.log。|
|完整Snapshot回归|33项通过，包含新增完整输入漂移/index字节不变；当轮综合命令因后续修复的Mockito重新设桩错误整体失败，不称整体成功。最终增加全量Entry比较后，专门复跑新增Snapshot公开入口1项通过，17:08:22，38.866秒，%TEMP%/b6c-drift-final.log。既有ReviewGitSafetyTest8项在172矩阵通过。|
|前端正式|最终68项、14文件通过；npm test，17:10:32；npm run build通过；既有>500KB chunk提示保留。%TEMP%/b6c-ui-final.log、b6c-ui-build-final.log。|
|Schema|68份元校验、331引用解析；新增6正/6额外字段反例通过；Java独立合同6项含黄金摘要对照及旧Head v1读回。|
|静态|显式15个新/受影响Review Java文件AOSP格式检查通过；其他原风格文件仅最小修改。git diff --check通过。|
|无关历史|output恢复清单13/13 SHA一致；本轮未写output或旧架构可视化。|

重点反例：失确认意图零提交；孤儿内容不可公开、原请求确认；同交接并发启动只有一Turn；已有失败Turn不再分配；Turn已存/Head失败恢复零重排队；无Turn重启零发送、显式同意图重试；未知Review保护；编辑替代/撤销/关闭只读；严格HTTP额外turnId拒绝/摘要冲突；所有Turn转换保留sourceHandoffId；浏览器恢复零自动发送。

## 剩余限制与复审入口

未跑付费模型/真实Coding、整批全量/干净环境、Legacy/Python/POSIX；Python执行逻辑未改，本轮不把历史测试冒称新鲜证据。真实CC能否正确理解交接/完成修复尚未验证；同模型语义盲点、公有权限描述偏差历史、256K真实高容量未验、超窗恢复DISABLED仍保留。JSON来源引用合法不保证模型判断正确，Prompt不能数学保证注入免疫，仍依赖既有路径/资源门禁。

独立复审入口：ReviewHandoffTest（服务/生产Turn/HTTP）、ReviewHandoffContractTest、SnapshotCaptureTest#handoffComparisonUsesCapturedFullInputAndDoesNotModifyIndex、ReviewHandoffPanel.test.tsx。请重点证伪跨Run STARTING保护、Head失确认与孤儿确认、变更/启动竞争、同Turn终态重试、过滤配置/index只读和容量边界。完成复审后才申请本切片提交，不进入后续批次。

最终专项确认：2026-10-14T17:12:17+08:00，44通过（Handoff服务/HTTP12、合同6、Report26），0失败/错误/跳过，41.993秒。此专项在最后Java可读性及条数限制移除后执行。未提交、未推送。


## B6C-R01：跨报告互锁最小补修（2026-10-14T17:24:44.636550+08:00）

### 定位与修复

真实生产链路中，Handoff.start只在写STARTING后进入Coding.submit的人工交互检查。同Session的另一OPEN报告造成确定拒绝，却留下无Turn的启动未知意图，反过来阻止关闭阻塞报告。
补修仅改两个产品文件：ReviewHandoffService在Session锁内、workspace检查与STARTING发布前调用guard.assertHandoffInteractionAllowed；ReviewExecutionGuard复用同一报告扫描/判定，预检查只把精确目标Run/Handoff的PREPARED或STARTING且匹配当前批准视为目标授权。其他OPEN报告仍按原规则阻断。普通Coding提交仍必须有真正STARTING，不能用PREPARED绕过；未回滚任何真正未知的启动、未分配替代Turn、未修改已有产物或增加API/Schema。
测试helper只增加指定Session导入重载。新增2个正式参数回归强制阻塞Run先/后扫描，均使用真实Result可靠导入、文件存储和生产Coding提交入口，队列明确拒绝、不运行CC：拒绝后PREPARED/零Turn/零队列调用→恢复仍PREPARED→能关闭B→相同A原请求只关联一个Turn→恢复/重复请求不新增。错Run预检查、普通提交不能借PREPARED授权也有断言。

### 本轮新鲜证据

- 红测：两种顺序均失败，预期PREPARED实际STARTING；%TEMP%/b6c-r01-red.log，2失败，0错误，17:19:53完成。
- 绿测：新增2项通过；%TEMP%/b6c-r01-green.log，17:20:59完成。随后加入错Run/普通提交反例并纳入最终矩阵。
- 最终受影响矩阵：服务目录 ../../mvnw.cmd '-Dtest=Review*Test,CodingTurn*Test,CodingSession*Test,FileCodingTurnRepositoryTest,SessionWorkspaceGuardTest' test，174通过、0失败/错误/跳过，2026-10-14T17:23:49+08:00，2:08；%TEMP%/b6c-r01-regression.log。包含Handoff14、合同6、Report26，以及原未知启动/失确认/重启/并发/来源/资源/Session/Coding门禁。
- 原仓库外MultiReportProbe/MultiReportInput未改源码，用当前产品/测试类重新编译至独立临时目录复跑，Java退出0；记录 START_REJECTED、HANDOFF=PREPARED TURNS=0，无CLOSE_OTHER_REJECTED/RETRY_REJECTED。路径 C:/Users/ROG/AppData/Local/Temp/b6c-r01-probe-1tz4su9b/probe.log。第一次输出包装脚本因GBK控制台解码打印失败，Java探针自身已退出0，后续只读取原日志，未重跑探针或隐藏失败。
- 明确四个受改Java文件AOSP格式检查、git diff --check通过。

### 证据适用范围与限制

本轮未改UI、Snapshot、Schema或Python实现，前轮前端68/构建、Snapshot1/原Git只读保护及Schema证据仍属于历史适用证据，不冒称本轮重跑。未付费、未运行真实Coding、未跑整批全量/干净环境/Legacy/POSIX。历史已有STARTING未知记录不自动解除或迁移；补修阻止新的确定拒绝制造此状态，不能把明确不存在Turn泛化为可安全抹除未知启动。原模型质量/256K高容量/恢复DISABLED限制不变。

修复已完成开发验收，B6C-R01待设计窗口独立复审，不自行认定整切片提交批准。未暂存、未提交、未推送，不进入后续批次。
