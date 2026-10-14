# 批次6B：人工报告版本与裁决（待独立复审）

日期：2026-10-14。基线 `5dca51d`，依据冻结稿51第6节及6B切片。仅离线开发，保持未提交/未推送；本轮未调用收费模型或启动真实Coding。

## 实现

Java新增严格Report Revision/View/Head及请求DTO，复用ReviewExecutionStore受管文件读写、原子单文件发布、写失确认后的精确字节重读，以及既有SessionOperationLockManager。没有新增数据库或第二套执行器。

报告只能从所属Session已经可靠导入、COMPLETED且资源RELEASED的真实Result2/3建立。每次操作仍校验原始事件、Result字节/hash/身份/版本/引用；历史GET不会调用协调器、重采当前Diff、创建报告或触发模型/Coding。关闭Session可读历史，创建、修改、批准及关闭人工审阅均拒绝。

ReportRevision保存reportId/revision、Session/Thread/Run、原Result ID/hash/version、Snapshot/manifest摘要、创建时间、findingReviews、用户要求和自身摘要。各Finding默认PENDING，原等级由平台从原Result注入并核对，用户只修订标题、描述、修改要求、备注和裁决。不得新增/删除/复活Finding或改变原等级、证据、源码引用和模型验证；原Result始终只读。人工修改不回填模型事实或消除历史错误。

显式POST创建，PUT提交完整人工字段（expectedRevision/requestId）。Session锁内重读报告头；陈旧版本409，同requestId相同请求返回原版本，不同请求409。不可变版本先发布，再发布单个ReportHead的当前指针、版本索引、请求回执、活动交互和批准选择。两文件不是原子事务。写确认失败保留已发布版本；公开GET只能读取头已确认的版本，孤儿不得成为批准对象。原请求可在服务重启后精确确认孤儿，不新分配报告身份或覆盖原内容。

批准提交精确revision/digest、非空selectedFindingIds、覆盖确认和用户要求，只能选择已保存为APPROVED_FOR_FIX的Finding。RISK/SUGGESTION保留原等级与原Result中的假设/验证。未完成覆盖、非六维全选或旧单Pass结果须确认有限/不完整覆盖。新版本清除当前批准选择，历史回执不反向恢复旧批准。零Finding可关闭人工审阅，不保存空批准。批准仅是持久人工选择，不创建Handoff或启动Coding。

前端提供显式开始审阅、裁决/字段编辑、保存新版本、历史版本只读、逐项批准选择、覆盖确认和关闭审阅，原模型草稿/范围/材料仍可查看。已知4xx拒绝允许修改后新提交；未知写入保留原请求及内容。同标签sessionStorage可跨导航/刷新恢复，恢复请求不自动发送。读取当前版本只在确认该请求或确定冲突时清除未知意图；重试沿用原requestId和原内容。完整所选六维覆盖不额外强制不完整确认，服务端独立校验。

## API与合同

基础路径：`/api/v1/coding-sessions/{sessionId}/review-runs/{runId}/report`。

- GET，可选`revision`：读取已发布当前/历史View，Cache-Control=no-store。
- POST：`requestId`，显式建立第1版。
- PUT：`expectedRevision/requestId/findingReviews/userInstructions`，完整替换人工字段并创建新版本。
- POST `/approve`：精确版本/摘要、请求身份、批准项、覆盖确认、用户要求。
- POST `/close`：精确版本/请求身份，关闭人工审阅，历史保留。

新增8份自包含严格Schema及8个正例/8个额外字段反例，Java业务校验补足Schema无法证明的身份、摘要、来源、权限与并发关系。内部ReportHead回执保存已确认的操作响应；Revision额外保存requestId/requestDigest以确认孤儿。旧合同与fixtures字节内容保持（按Git换行规范对照），外部Result2/3不改。新source-results是独立离线样例，涵盖RISK/SUGGESTION/零Finding/旧Result2，不改历史验收产物。

文本上限为UTF-8字节：标题2048、描述/修改要求各16384、备注4096、用户要求32768；单版本、读/写请求及报告头最大1MiB。开放报告头另预留1024字节给关闭操作；容量预检发生在新版本发布前。若先前已有孤儿且容量已变化，不把它改称新输入拒绝，仍保留原请求确认保护。版本/回执历史不会静默裁剪。

## 验收入口与范围

后端：ReviewReportTest（真实文件产品存储及可靠Result导入）、ReviewReportHttpTest（真实MVC参数/状态翻译）、ReviewReportContractTest（Schema与Typed DTO字段一致性、摘要及正反例）。覆盖历史不改、版本冲突/重复请求/并发单胜者、发布前后失确认/服务重启/孤儿不可读、零Finding、关闭Session、RISK/SUGGESTION不升级、旧Result2以及临近容量仍可关闭。

前端：ReviewReportEditor.test.tsx，覆盖显式创建、未知写入原请求重试、恢复无自动发送、已知校验失败可修正、批准选择/覆盖确认、历史版本与关闭会话只读。原ReviewDraftPanel测试保持。

日志根：`C:/Users/ROG/AppData/Local/Temp/code-weave-b6b-xmw2xd34`。最终新鲜计数、命令和起止时间在下方追加；旧矩阵不冒称最后补修后的结果。

## 保留限制与复审重点

6C尚未实施：无Handoff、工作区漂移复核、Coding启动或STARTING意图。后续须复用该ReportHead和Session锁，实现Handoff失效/撤销与启动未知保护；不将6B批准选择当作已启动Coding，也不从GET解除交互保护。

浏览器恢复限当前标签会话存储，关闭标签后不保证保留未确认请求。服务端仍须原API请求身份/内容才能确认孤儿；不猜测、自动回收或删除已发布内容。累积文件预算达到上限会明确拒绝新修改，保留关闭空间。本轮未跑Python全量/干净环境、Java/前端Legacy矩阵、真实模型或POSIX；Python运行实现未改。public描述偏差、同模型验证局限、256K高容量未验及超窗恢复DISABLED继续保留。

请设计窗口独立复审来源/等级不可改、版本/请求竞争、指针失确认、孤儿权限、历史只读、覆盖确认及前端重试语义，再授权提交；不进入6C。

本轮发生一次范围选择错误：格式化误选output中13个历史Java探针。已从原创建/更新补丁恢复内容，全部与误改文件的格式化结果一致，恢复SHA记录保存在日志根output-restoration.json。没有把这些产物纳入提交；不宣称恢复了原NTFS修改时间。其余原可视化、Result/Checkpoint与仓库外历史日志未改写。


## 最终新鲜验收（开发证据，待独立复审）

| 验收 | 结果 | UTC起止/日志 |
| --- | --- | --- |
| Java受影响Review/Session控制器矩阵 | 73通过，0失败/错误/跳过；Maven1分47秒 | 2026-10-14T07:12:05.828633+00:00—2026-10-14T07:13:55.709078+00:00；java-affected.json/log |
| 最终报告专项 | 22通过，0失败/错误/跳过；Maven22.664秒 | 2026-10-14T07:33:55.844323+00:00—2026-10-14T07:34:20.310321+00:00；java-report-final.json/log；报告头容量/前端纠错补修后重跑 |
| 前端正式全量 | 13文件64通过，6.95秒 | 2026-10-14T07:33:55.886055+00:00—2026-10-14T07:34:03.826990+00:00；ui-test-final.json/log |
| 前端正式构建 | 通过，保留既有>500KB分块提示 | 2026-10-14T07:34:03.827993+00:00—2026-10-14T07:34:05.581963+00:00；ui-build-final.json/log |
| Schema | 62份元校验、282引用；8正例/8反例通过；旧合同内容按Git换行规范不变 | schema.json |
| Java格式/差异 | 10个6B源文件格式通过；git diff --check通过 | java-format.log、diff-check.log、final-checks.json |
| Python运行、干净环境、Legacy、真实模型/Coding、POSIX | 本轮未验 | Python运行实现未改，不冒称历史结果新鲜重跑 |

较早73矩阵之后，自查补了“400确定拒绝也锁住表单”的UI红测并修复；同时把报告头容量校验前移，并预留关闭空间。最终按影响重跑报告22项、前端全量64项/构建。22中原21与73重叠，不相加宣称95个独立用例；前端64代替较早63计数。HTTP1.1/Result3与旧Result2的可靠导入由原生产读端重新核对，不以人工JSON替换历史Result。

命令（services/control-plane）：
```powershell
../../mvnw.cmd '-Dtest=Review*Test,CodingSessionControllerTest' test
../../mvnw.cmd '-Dtest=ReviewReport*Test' test
```
命令（apps/web-console）：`npm test`、`npm run build`。Schema采用Draft202012Validator及本地Registry解析，Java合同专项逐项比较Schema字段与Record字段并运行正反例。所有日志保存准确命令、基线、cwd、UTC起止、退出码。

首次TDD阶段测试入口缺类失败后实现；另修正一次测试命令cwd、缺失ChangePolicy导入和新Java文件的Windows默认编码，均完成重新验证，无产品测试失败遗留。

仓库外问题日志仅追加并核对旧字节前缀；output恢复内容的13个SHA再次核验通过。HEAD仍5dca51d，暂存区为空。状态：6B离线交付，待设计窗口独立复审，不自动提交/推送或进入6C。


## B6B-R01 最小补修（2026-10-14T16:08:52.435644+08:00）

原因：原 Result claim/suggestion 的 4096 Unicode 字符与人工字段 8192 UTF-8 字节不兼容。
补修：仅 description/fixInstructions 统一为 4096×4=16384 UTF-8 字节；Schema 原有 8192 字符限制保留。无损复制原文、不修改原 Result；整体1MiB、Head预检和关闭空间保护不变。
正式新增四组真实可靠导入回归（Result2/3 × 中文/四字节字符，各4096字符）：创建、持久读取、仅备注修订均保留 claim/suggestion；原 Result 字节不变；两个人工字段4097个四字节字符均拒绝。
红测：四组在严格导入后均因 REPORT_TOO_LARGE 创建失败；见 C:/Users/ROG/AppData/Local/Temp/b6b-r01-red.log。
绿测：services/control-plane 下 ../../mvnw.cmd '-Dtest=ReviewReport*Test' test，2026-10-14T16:07:56+08:00完成，26通过（合同8、HTTP1、报告17），0失败/错误/跳过，24.313秒；见 C:/Users/ROG/AppData/Local/Temp/b6b-r01-green.log。
62份Schema元校验、三个受改Java文件AOSP格式检查、git diff --check通过。
本轮写测试时默认GBK编码导致原 ReviewReportTest.java 清空，已从历史开发工具记录重建原13项测试并重跑全部通过；未对恢复前文件留存SHA，不能宣称逐字节恢复。恢复操作严格限定该测试文件，没有触碰output历史产物。新增测试与helper明确使用UTF-8写入。
前端/Python执行逻辑及原Result合同未改；前端64项/构建属于此前证据，本轮未重跑，不影响此前适用范围。未付费、未暂存、未提交、未推送、不进入6C。待设计窗口独立复审。
