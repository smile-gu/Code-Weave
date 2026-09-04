import type { CodingTurn, RunEvent } from '../../shared/api/types'
import { eventSummary } from '../run-monitor/runProjection'
import type { StageKey, StageStatus, StageView } from '../run-monitor/runProjection'

/** 将实时 Turn 事件压缩为固定五阶段，避免把 Agent 内部事件直接暴露给用户。 */
export function projectTurnStages(events: RunEvent[], turn?: CodingTurn | null): StageView[] {
  const stages = createStages()
  const byKey = new Map(stages.map((stage) => [stage.key, stage]))
  const coding = byKey.get('coding')!

  for (const event of events) {
    if (event.type === 'RUN_QUEUED') {
      coding.summary = '本轮已进入队列，等待 Coding Agent。'
    } else if (event.type === 'RUN_STARTED') {
      setStage(coding, 'running', event.timestamp, undefined, 'Coding Agent 已启动，正在处理本轮任务。')
    } else if (event.type === 'AGENT_EVENT_OBSERVED') {
      const summary = agentSummary(event)
      if (agentEventType(event) === 'RUNTIME_COMPLETED') {
        setStage(coding, 'completed', coding.startedAt ?? event.timestamp, event.timestamp, summary)
        setStage(byKey.get('change-collection')!, 'running', event.timestamp, undefined, '正在读取真实工作区中的 Git 变更。')
      } else {
        setStage(coding, 'running', event.timestamp, undefined, summary)
      }
    } else if (event.stage === 'maven' && event.type === 'STAGE_STARTED') {
      complete(coding, coding.startedAt ?? event.timestamp, event.timestamp, coding.summary ?? 'Coding Agent 已完成本轮任务。')
      complete(byKey.get('change-collection')!, event.timestamp, event.timestamp, '代码变更已经采集完成。')
      complete(byKey.get('change-policy')!, event.timestamp, event.timestamp, '路径边界检查通过。')
      setStage(byKey.get('maven')!, 'running', event.timestamp, undefined, eventSummary(event))
    } else if (event.stage === 'maven' && event.type === 'STAGE_COMPLETED') {
      complete(byKey.get('maven')!, event.timestamp, event.timestamp, eventSummary(event) ?? 'Maven 验证通过。')
    } else if (event.stage === 'maven' && event.type === 'STAGE_FAILED') {
      setStage(byKey.get('maven')!, 'failed', event.timestamp, event.timestamp, eventSummary(event) ?? 'Maven 验证未通过。')
    } else if (event.type === 'RUN_COMPLETED' || event.type === 'RUN_FAILED' || event.type === 'RUN_CANCELLED') {
      applyTerminal(byKey, event, turn)
    }
  }

  // SSE 终态和 Turn 快照可能不是同一时刻到达，以持久 Turn 结论做最终校准。
  if (turn && !isActive(turn.status)) applyOutcome(byKey, turn)
  if (turn?.status === 'QUEUED' && !events.some((event) => event.type === 'RUN_QUEUED')) {
    coding.summary = '本轮已创建，等待执行。'
  }
  if (turn?.status === 'RUNNING' && coding.status === 'pending') {
    setStage(coding, 'running', turn.startedAt, undefined, 'Coding Agent 正在执行本轮任务。')
  }
  return stages
}

/** 为五阶段创建初始视图；真实 Git 工作区在 Session 创建时已绑定。 */
function createStages(): StageView[] {
  return [
    { key: 'workspace', label: '真实工作区', description: '检查 Session 仓库基线', status: 'completed', startedAt: null, endedAt: null, summary: '当前 Turn 已绑定并检查真实 Git 工作区。' },
    { key: 'coding', label: '代码执行', description: '在当前代码工作区完成任务', status: 'pending', startedAt: null, endedAt: null },
    { key: 'change-collection', label: '变更采集', description: '生成文件清单与 Unified Diff', status: 'pending', startedAt: null, endedAt: null },
    { key: 'change-policy', label: '路径门禁', description: '确认改动未越过任务边界', status: 'pending', startedAt: null, endedAt: null },
    { key: 'maven', label: 'Maven 验证', description: '独立执行编译与测试', status: 'pending', startedAt: null, endedAt: null },
  ]
}

/** 根据终态事件先给出阶段结论，随后由持久 Turn 快照精确校准。 */
function applyTerminal(byKey: Map<StageKey, StageView>, event: RunEvent, turn?: CodingTurn | null) {
  const coding = byKey.get('coding')!
  if (event.type === 'RUN_COMPLETED') {
    setStage(coding, 'completed', coding.startedAt ?? event.timestamp, event.timestamp, 'Coding Agent 已完成本轮任务。')
  } else {
    setStage(coding, 'failed', coding.startedAt ?? event.timestamp, event.timestamp,
      event.type === 'RUN_CANCELLED' ? '本轮已停止，真实工作区修改仍然保留。' : '本轮 Coding Agent 未能完成。')
  }
  if (turn) applyOutcome(byKey, turn)
}

/** 把持久化 Turn 结论映射到变更采集、路径门禁和 Maven 三个阶段。 */
function applyOutcome(byKey: Map<StageKey, StageView>, turn: CodingTurn) {
  const coding = byKey.get('coding')!
  const collection = byKey.get('change-collection')!
  const policy = byKey.get('change-policy')!
  const maven = byKey.get('maven')!
  const end = turn.endedAt

  if (turn.status === 'FAILED' && turn.outcome === 'AGENT_FAILED') {
    setStage(coding, 'failed', coding.startedAt ?? turn.startedAt, end, turn.userMessage)
    return
  }
  if (turn.status === 'CANCELLED' || turn.status === 'INTERRUPTED') {
    setStage(coding, 'failed', coding.startedAt ?? turn.startedAt, end, turn.userMessage)
    return
  }
  if (turn.outcome === 'NO_CHANGES') {
    complete(coding, turn.startedAt, end, 'Coding Agent 已完成；未发现代码修改。')
    complete(collection, turn.endedAt, end, '未发现代码修改，变更清单为空。')
    complete(policy, turn.endedAt, end, '没有代码修改，路径门禁无需拦截。')
    maven.summary = '没有代码修改，本轮未运行 Maven。'
    return
  }
  if (turn.outcome === 'POLICY_VIOLATION') {
    complete(coding, turn.startedAt, end, 'Coding Agent 已完成本轮任务。')
    complete(collection, turn.endedAt, end, `已采集 ${turn.changedFiles} 个变更文件。`)
    setStage(policy, 'failed', turn.endedAt, end, '发现超出 Session 允许路径的修改，Maven 已跳过。')
    return
  }
  if (turn.outcome === 'VERIFICATION_FAILED') {
    complete(coding, turn.startedAt, end, 'Coding Agent 已完成本轮任务。')
    complete(collection, turn.endedAt, end, `已采集 ${turn.changedFiles} 个变更文件。`)
    complete(policy, turn.endedAt, end, '路径边界检查通过。')
    setStage(maven, 'failed', turn.endedAt, end, 'Maven 验证未通过，请查看 Turn 结果中的失败信息。')
    return
  }

  if (turn.outcome === 'WORKSPACE_DRIFT') {
    setStage(coding, 'failed', coding.startedAt ?? turn.startedAt, end, turn.userMessage)
    collection.summary = '工作区状态已变化，未继续采集代码变更。'
    policy.summary = '工作区门禁已阻断，本轮未执行路径检查。'
    maven.summary = '工作区门禁已阻断，本轮未运行 Maven。'
    return
  }
  if (turn.outcome === 'CHANGES_VERIFIED') {
    complete(coding, turn.startedAt, end, 'Coding Agent 已完成本轮任务。')
    complete(collection, turn.endedAt, end, `已采集 ${turn.changedFiles} 个变更文件。`)
    complete(policy, turn.endedAt, end, '路径边界检查通过。')
    complete(maven, turn.endedAt, end, 'Maven 编译与测试通过。')
  }
}

/** 将一个阶段推进到指定状态，并保留已知时间。 */
function setStage(stage: StageView, status: StageStatus, startedAt: string | null | undefined, endedAt: string | null | undefined, summary?: string) {
  stage.status = status
  stage.startedAt = stage.startedAt ?? startedAt ?? null
  stage.endedAt = endedAt ?? stage.endedAt
  if (summary) stage.summary = summary
}

/** 将阶段标记为完成。 */
function complete(stage: StageView, startedAt: string | null | undefined, endedAt: string | null | undefined, summary: string) {
  setStage(stage, 'completed', startedAt, endedAt, summary)
}

/** 兼容 Coding 当前的 eventType 字段，并复用已有中文 Agent 摘要。 */
function agentSummary(event: RunEvent): string {
  const type = agentEventType(event)
  const normalized: RunEvent = type === event.payload.agentEventType
    ? event
    : { ...event, payload: { ...event.payload, agentEventType: type } }
  return eventSummary(normalized) ?? 'Coding Agent 正在处理本轮任务。'
}

/** 读取单轮与多轮事件协议中的 Agent 类型字段。 */
function agentEventType(event: RunEvent): unknown {
  return event.payload.agentEventType ?? event.payload.eventType
}

/** 判断 Turn 是否仍处于执行中。 */
function isActive(status: CodingTurn['status']) {
  return status === 'QUEUED' || status === 'RUNNING'
}
