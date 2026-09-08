# ADR-0012：阶段事实事件与统一 Agent 协议

- 状态：当前有效
- 日期：2026-09-08
- 前置约束：[ADR-0011](0011-coding-turn-terminal-reliability.md)

## 目标与事实归属

CodingTurn 快照决定任务当前状态，阶段事件决定过程事实，Agent 事件只提供活动详情。前端不再从任务结果、Agent 输出或 Maven 报告推断阶段完成。

## 五阶段边界

| 稳定标识 | 开始到结束的工作 |
|---|---|
| workspace | Orchestrator 初始工作区门禁，不含创建 Session、排队和提交前校验 |
| coding | Claude 调用、原始事件处理、Runner 返回及资源收敛 |
| change-collection | Agent 后工作区检查、累计 Diff、指纹计算及复查 |
| change-policy | 对累计变更计算路径约束 |
| maven | 验证前检查、Maven 执行、验证后检查和指纹一致性确认 |

每 Turn 创建一个 CodingStageLifecycle，attempt 固定为 1。不共享跨 Turn 可变状态，不管理线程、进程、Session 保存或终态裁决。

## 阶段协议

保留外层 schemaVersion、eventId、runId、stage、attempt、sequence、timestamp、type、payload。仅增加载荷协议版本 stageProtocolVersion = 1。

| 事件 | 必要载荷 |
|---|---|
| STAGE_STARTED | 协议版本、message |
| STAGE_COMPLETED | 协议版本、message |
| STAGE_FAILED | 协议版本、message、failureCode、failureKind，可选 causeCode |
| STAGE_SKIPPED | 协议版本、message、reasonCode，可选 causedByStage |

未开始只能变为开始或跳过；开始只能变为完成或失败。已结束不可重开，不重复结束，最多一个活动阶段。跳过原因包括 NO_CHANGES、UPSTREAM_FAILED、TURN_CANCELLED、TURN_NOT_STARTED。

路径越界：路径阶段失败、Maven 跳过，Turn 仍为 COMPLETED / POLICY_VIOLATION。测试未通过：Maven 阶段失败，Turn 为 COMPLETED / VERIFICATION_FAILED。取消的活动阶段使用 FAILED / CANCELLED，产品显示“已停止”，后续阶段跳过。清理失败优先级更高，不确认取消成功。

## 诊断及持久化

Turn 新增可选 failedStage、failureKind，复用 failureCode；旧快照缺字段按 null 读取，Turn 文件 schema 不升级。业务门禁失败也保留阶段诊断。启动前、阶段之外的持久化失败不强行归因 coding。

| 场景 | failureCode | failureKind |
|---|---|---|
| 工作区基础设施异常 | WORKSPACE_CHECK_FAILED | INFRASTRUCTURE |
| Agent 返回失败 | AGENT_EXECUTION_FAILED | AGENT |
| 变更采集异常 | CHANGE_COLLECTION_FAILED | INFRASTRUCTURE |
| 路径计算异常 | CHANGE_POLICY_EVALUATION_FAILED | INFRASTRUCTURE |
| 路径越界 | CHANGE_POLICY_REJECTED | POLICY |
| Maven 基础设施异常 | MAVEN_EXECUTION_FAILED | INFRASTRUCTURE |
| 编译/测试未通过 | MAVEN_VERIFICATION_FAILED | VERIFICATION |
| 验证期间变化 | WORKSPACE_CHANGED_DURING_VERIFICATION | WORKSPACE_DRIFT |
| 清理未确认 | PROCESS_CLEANUP_FAILED | RESOURCE_CLEANUP |
| 事实写入未确认 | EXECUTION_EVENT_PERSISTENCE_FAILED | INFRASTRUCTURE |
| 用户停止 | CANCELLED | CANCELLED |

已有工作区具体错误码保留。底层 Runner 状态通过 causeCode 保留。系统异常细节写服务诊断日志，不以异常类名作为公开协议。

阶段开始写入失败，不执行工作；阶段结束写入失败，不开始下一阶段。发生不确定写入后封闭生命周期，不循环补写失败事实。Runner 仍必须清理资源；清理失败不被后续日志异常覆盖。

应用层保持 Session 锁内裁决，先必要 Session 更新、再 Turn、最后尽力通知。终态通知从已保存 Turn 生成诊断。排队取消和启动失败只记录未执行阶段；恢复不依据残缺日志猜测补写阶段结果。全部阶段完成后的取消或保存失败，不修改已有阶段事实。

## 统一 Agent 映射

正式 Coding 与 Legacy 包装器均依赖 observability.agent.AgentEventPayloadMapper；正式代码不依赖 Legacy。

新版载荷：agentProtocolVersion = 1、agentEventType、externalId、payloadBytes、payloadSha256、payloadTruncated，以及 payload 或 payloadPreview。不再写 eventType 别名。

同一份原始 UTF-8 JSON 序列化字节计算大小与 SHA-256，阈值 16 KiB（等于阈值仍保留完整载荷）。超限预览在完整 UTF-8 字符边界截断；它是文本，不承诺完整 JSON。哈希不提供 JSON 语义规范化。externalId 原样保留，缺失为 null；不截断或生成关联 ID。Mapper 不修改输入，业务原生会话绑定和助手文本提取继续消费原始事件。

## 前端与历史

shared/events/eventProtocol 集中兼容旧正式 eventType、旧 Legacy agentEventType、新版 version 1，历史缺失的 ID/大小/哈希不伪造。关联键包含 Turn，缺 ID 的活动独立显示。所有事件按 eventId 去重、sequence 排序。

正式阶段栏和活动时间线只接收受支持且字段有效的阶段事实。Agent、Maven 报告使用独立活动，不覆盖阶段结论。迟到开始不重开已结束阶段；未知协议安全降级。

历史无阶段事实显示“历史记录未提供完整阶段信息”；已持久终结而阶段不完整时关闭运行中动画，显示记录不完整，不补造失败或跳过。继续保留 SSE 终态查询确认、有限退避与低频单 Turn 对账。

已结束 Turn 通过一次性 events HTTP 查询读取历史，避免在缺失终态通知时持续等待 SSE。加载中、加载失败和确认缺少证据分别展示；这不是事件轮询或新的存储系统。请求按 Session/Turn 隔离并可取消，迟到响应不覆盖下一轮。

## 不做的事

不新增 Review、自动重试、断点续跑、不可变 Turn Diff、正常 Session 关闭、可靠消息投递系统或事件存储重构；不回填历史。Legacy 仅必要协议适配与回归维护。
