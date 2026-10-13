# 批次 5 Evidence 专项补修交付

2026-10-11；对应仓库外专项复审 26 的 B5-R01/R02/R03。仅离线开发及验收，付费请求 0。
本记录供独立复审，不替代真实闭环验收，也不授权提交或进入下一批。

## 来源投影

Gateway 的 `data.evidenceReferences` 提供规范、可复制的来源对象；旧 sourceRefs 保留为
Context 追踪输入。final 的 sha256 是原始整文件摘要，side/sourceType 显式提供；
模型可在观察到的范围内选行，不自行计算摘要。原用户输入在真实调查 anchors 中提供
USER_INPUT 引用和保守、合法的候选示例；模拟图和压缩请求不增加该生产者声明。

Diff 页携带全文件物理 LF 行号、首尾半行标记和有效 hunk 引用，header 不可引用。
投影与 Evidence 校验共用 diff_lines，支持删除与旧/新路径；base 仍明确不支持。
模型通过连续 Cursor 拼接半行，源摘要始终指向完整固定 changes.diff。
连续读完未脱敏 Diff 后可以确认完整覆盖；跳页与脱敏不能确认 FULL。

Artifact 内容页 Schema 增加可选严格 coordinates；其坐标属于存储后的 Artifact，
不能代替原源码坐标。Gateway 为可信 TOOL 材料提供 ARTIFACT 引用，SYSTEM 材料不提供
CODE 生产者身份。正文和新元数据共同受输出预算控制；页缩小正文，保留 Cursor，不递归转存。
小到放不下页面协议的预算返回 TOOL_LIMIT，不制造资源失败。旧无坐标样例仍合法。

## 纠错与整批发布

JSON、Schema 和明确可修正的引用错误共用 correctionCount，最多两次纠错，计入同一模型总预算。
反馈包含稳定 reason、阶段、白名单字段路径和具体索引；禁止通用 catch 后继续模型调用。
Scope、已确认 Evidence/Candidate 引用与全部材料先校验，再用既有 Evidence 表单事务保存整批。
第二条失败没有第一条事实发布；成功发布后统一回执，State 与事实引用相符。
提交丢确认独立核对全部不可变记录；发布/Checkpoint 故障不进入语义重试。

Snapshot 内容与其 Manifest 不匹配是完整性故障，和模型提交的错误摘要分开处理。
存储失败、Artifact 损坏、取消与未知资源继续立即阻断。已有 Artifact 脱敏状态传入材料覆盖，
已脱敏材料不能被 FULL Evidence 掩盖；不是重新扫描已脱敏文本后冒称 NONE。

## 诊断与保密

诊断复用 SYSTEM TOOL_OUTPUT Artifact，按 Run/Snapshot 归属、认证读取与完整性约束保存。
内容含 modelCallId、submissionDigest、候选/引用索引、字段路径、阶段、稳定原因和
Schema 校验后的受控引用对象。未知路径隐藏；不存原始提交、未知字段名、模型叙述或异常消息。
原响应本身有 64 KiB 边界，诊断还受 Artifact 捕获/总额/数量预算控制；仅 FULL 诊断允许纠错。
诊断引用进入下一实际请求并计量，诊断本身不能作为 CODE Evidence。
诊断写失败保留原始拒绝门禁并停止，不产生成功或继续付费。

受控引用可从同一固定输入离线重建错误 hash、越界行及脱敏覆盖冲突。
旧第四轮没有原结构提交，无法反推其具体非法字段；本次修复不补造历史诊断。

## 新鲜验收

- Python 全量：382 通过，310.90 秒，独立临时根。
- 原审查样例/生产 App/协议专项：43 通过；后续覆盖完整投影、故障与 Schema 专项 46 通过。
  这些属于不同时间点的开发证据，最终以 382 项全量为准，不相加宣称全量计数。
- 新增 17 项正式回归：只从生产请求/实际工具信封提取摘要，不给脚本模型预先注入文件 hash。
  覆盖 final/USER_INPUT、删除 Diff 跨页/半行、Artifact 坐标/预算、语义纠正、两次上限、
  整批保存/失确认、独立 VALIDATION 反例拒绝、存储/完整性/诊断/取消阻断和脱敏覆盖。
- Java 正式：`mvnw.cmd -pl services/control-plane -am clean test -q` 通过；本轮 50 个 XML，
  286 项，failure/error/skip 0，验证共享资源打包及既有导入。
- Ruff/check format：114 文件，通过；相关差异检查通过。
- 干净 Python 3.12：382 通过，300.43 秒；精确锁安装、pip check、显式迁移、
  服务启动和认证 Readiness 通过；Ruff/format 114 文件通过。
  本轮日志保留在系统临时目录 code-weave-evidence-clean-770ef281a9ee4ac4abf99a4b0bbd42a6.log。

Java Legacy、前端正式/Legacy本轮未重跑：未修改相应实现，历史证据与本轮证据分开。
POSIX 未验。本轮未读取密钥、未调用真实 API；项目未暂存、未提交、未推送。

## 仍需复审与真实验收

先交独立复审确认三个平台缺口的补修。之后另确认真实请求与费用额度；原真实窗口已用
15/16 次，剩余一次不等于足够闭环额度。不得自动扩大或重放旧 Run。
真实小样仍须到 Evidence、独立 VALIDATION、Result 才能宣称 Python 真实闭环通过；
Java/UI 与实际压缩续接仍需各自证据。离线脚本结果不代表真实模型质量已通过。


## B5-R04 补修（2026-10-11）

hunk 内前缀代码按旧/新剩余行数解析，文件头与无末尾换行标记保持不可引用。正式红测已复现空投影；新增 5 组真实 Git/多文件/多 hunk/零计数/CRLF/跨页回归。新鲜专项 56、Python 全量 387（330.58 秒）通过；Ruff/format 114 文件及差异检查通过。Java/前端/干净环境本轮未重跑，真实请求 0，保持未提交、未推送。完整记录见 docs/review-harness-batch-5-diff-parser-fix.md；待独立复审，真实闭环仍待验。


## 第五轮结构提交纠错补修（2026-10-11）

离线红测确认拒绝回复未进入纠错请求、验证隔离视图遗漏反馈、大回复仅有预览，以及反馈缺少具体校验约束。已复用有界 State/Artifact/请求计量保存脱敏回复并装配完整纠错视图；新增最多 8 条白名单 schemaViolations，约束仅从平台 Schema 提取；明确先证据确认再引用 ID 的阶段。结构化脱敏保留 JSON，不把原回复放入诊断，不放宽 Schema/引用/身份或两次纠错门禁。最终专项 40、Python 全量 395（374.49 秒）、Ruff/format 115 文件及差异检查通过。Java/前端/干净环境未重跑，额外真实请求 0；真实闭环仍未重验。详见 docs/review-harness-batch-5-submission-correction-fix.md，待独立复审，未暂存/提交/推送。
