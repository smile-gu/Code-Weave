# 批次6：已有真实Result的Java/UI隔离跨层验收

日期：2026-10-17（Asia/Shanghai）。基线eea5b1f；原真实输入Run8640c4ef-6925-4e7f-adcd-04ad5c5434e4，Result3.0八条Finding。未生成新模型Run、未读取密钥、未付费、未运行Coding、未改产品、未提交推送。

## 结论与边界

已有Python真实Result原字节经当前Java严格导入、报告人工修订、批准及准备交接完成；生产前端通过隔离HTTP桥接实际Java报告/Handoff控制器与服务，完成浏览器编辑版本3、批准和再次准备交接。原模型Result/历史版本不变，旧交接SUPERSEDED，新交接PREPARED，零Turn/零启动请求。

这是**真实Result原字节＋真实Java领域服务/控制器＋生产前端的隔离验收**，不是正式Spring Boot部署全链实测：HTTP监听由仓库外JDK HttpServer承载并转发MockMvc；会话、变更、基线、Result读取的部分周边查询为隔离投影；仓库工作区检查使用空实现、Coding服务为Mock且/start入口硬阻断。未验证真实Git漂移保护、正式Spring Security/过滤器、Java到Python在线HTTP/SSE或真实CC。不可将这些未验项写成通过。

## 固定原件与装配

- 原证据根C:/Users/ROG/AppData/Local/Temp/code-weave-b6-partition-real-8yhumign/real，只读request/status/events/result，不改Snapshot、原数据库或Artifact。
- 原Result SHA256：312727039ce9b3133502aff7e64503dbe9cc3576bd1bf0dfa84329ae8684876b。
- 新隔离证据根C:/Users/ROG/AppData/Local/Temp/code-weave-b6-cross-layer-0c175640，Java持久化使用该根下java-runtime，不使用项目runtime或真实仓库。
- javac从当前产品源码重新编译212个正式Java文件＋1个外部验收探针，使用既有缓存依赖；按Maven资源规则复制当前contracts/schemas到隔离review-schemas，不依赖旧target类。编译退出0。
- npm run build新鲜通过；生产dist供浏览器读取。构建有既有大于500kB bundle警告，不是构建失败，未顺带调整打包策略。

## Java原字节接力

RealResultChain.java重建原ReviewExecution提交身份，请求字节与原request完全一致。全部历史事件双重导入、finish双重调用，严格Result3/持久化校验，导入终态COMPLETED、原结果字节不变。

创建报告版本1（8项原判断）后，对c11作人工修订：两方法对null均抛NPE、显式/隐式机制不同，nullableLength仍违反保留的null返回0契约。生成版本2、保留其原始等级与来源；其他项暂缓，只批准c11修订项。准备交接幂等读回，材料同时包含人工修正文与原Finding错误原文，不能把人工修正冒称模型自纠。

Java阶段断言：版本1不可变、Result不可变、交接PREPARED且只含选中项、Coding mock零交互、Turn仓库为空。java-summary.json记录成功；随后进程作为浏览器桥接服务持续运行，不把监听进程称作已自然退出exit0。

## Playwright浏览器交互

依据playwright技能执行CLI，使用独立命名session b6-real-result，不使用Playwright测试替换生产前端。

1. 打开隔离会话，选择真实COMPLETED Review，进入人工报告与版本。
2. 当前版本2的c11描述显示人工修正；切到版本1，原opposite null handling错误仍存在，编辑控件禁用。
3. 回到版本2，实际在浏览器修改c11备注，点击保存为新版本。经正式ReviewReportController/ReviewReportService持久化为版本3；版本2旧交接显示已被新版本替代。
4. 浏览器勾选该修订项，保存批准选择，再点击准备修复交接。新交接为版本3/PREPARED，保留修正文及浏览器备注；没有点击启动Coding。
5. 独立verify_browser.py通过HTTP读回版本3、版本1、批准和交接，核对新旧交接状态、原Result和历史内容；检查请求日志没有/start或POST turns，临时Turn目录无文件。

浏览器写入通过真实Java控制器处理，不是用返回固定JSON的替身模拟保存成功。控制器转发之外的隔离周边查询不扩大为正式业务验收。

## 新鲜证据与错误区分

- javac 213个源码编译退出0；javac.args/log、classpath.txt、探针及脚本留档。
- npm生产构建退出0；Java断言成功标记REAL_RESULT_JAVA_CHAIN_PASS，java-summary.json。
- human-current/history/v3/approved及handoff-v3.yml快照；handoff-v3.png截图；browser-requests.jsonl；browser-verification.json及最终报告/历史/Handoff HTTP读回副本。
- verify_browser.py退出0，BROWSER_JAVA_CROSS_LAYER_PASS。
- 浏览器及监听60698的本次Java服务已关闭；停止前核对PID24864为包含本证据根和RealResultChain的自有java进程，未终止其他应用。
- git diff --check通过，产品无已跟踪变更。既有output/及原架构可视化未修改，本轮只新增验收文档和仓库外证据。

装配阶段失败保留解释，不算产品缺陷：探针最初有括号笔误、javac本地化错误解码失败；PowerShell未引号Java -D参数被错误拆分；隔离类路径初缺Maven打包的review-schemas。均仅修复仓库外装配后重新编译运行。浏览器首次访问旧/coding-sessions路由重定向列表，而周边列表投影不符合真实列表合同导致前端错误；改为源代码确认的/sessions/:id详情入口后验收。另一次历史切换后旧ref失效，重新snapshot取得新ref，无产品修改。不能将初期错误记录当最终通过或隐去。

## 尚未证明

人工纠正c11验证了“可审阅、可修订、可追溯、明确批准再交接”的兜底流程，不证明模型语义偏差已修复。未重新运行Java完整JUnit/UI组件/Legacy矩阵，不能把外部探针算作这些正式测试数量；其历史证据保留。没有新Provider通信、真实Coding或真实工作区启动检查。

下一步可将最终783离线证据与本隔离跨层证据交设计收口；正式部署/真实Git/真实Coding验收如需执行，应另定范围并由用户显式授权，不能自动以已批准报告触发Coding。
