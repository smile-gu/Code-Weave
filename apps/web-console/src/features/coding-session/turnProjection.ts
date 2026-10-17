import type { CodingTurn, RunEvent } from '../../shared/api/types'
import { codingStageIds, isStageFact, orderedEvents } from '../../shared/events/eventProtocol'
import type { StageView } from '../run-monitor/runProjection'

const labels = ['工作区检查', 'Agent 执行', '变更采集', '路径门禁', 'Maven 验证']

/** 只投影后端阶段事实，Turn 终态仅用于停止不完整记录的运行中动画。 */
export function projectTurnStages(events: RunEvent[], turn?: CodingTurn | null): StageView[] {
  const stages: StageView[] = codingStageIds.map((key, index) => ({
    key, label: labels[index], description: '等待阶段事实', status: 'pending',
    startedAt: null, endedAt: null,
  }))
  for (const event of orderedEvents(events)) {
    if (!isStageFact(event)) continue
    const stage = stages.find((item) => item.key === event.stage)!
    if (['completed', 'failed', 'stopped', 'skipped'].includes(stage.status)) continue
    if (event.type === 'STAGE_SKIPPED' && stage.status !== 'pending') continue
    if (event.type === 'STAGE_STARTED' && stage.status !== 'pending') continue
    stage.summary = String(event.payload.message)
    if (event.type === 'STAGE_STARTED') {
      stage.status = 'running'
      stage.startedAt = event.timestamp
    } else {
      stage.status = event.type === 'STAGE_COMPLETED' ? 'completed'
        : event.type === 'STAGE_SKIPPED' ? 'skipped'
          : event.payload.failureKind === 'CANCELLED' ? 'stopped' : 'failed'
      stage.endedAt = event.timestamp
      if (stage.status === 'skipped') stage.summary = '已跳过：' + stage.summary
      if (stage.status === 'stopped') stage.summary = '已停止：' + stage.summary
    }
  }
  if (turn && turn.status !== 'RUNNING' && turn.status !== 'QUEUED') {
    for (const stage of stages) {
      if (stage.status === 'running' || stage.status === 'pending') {
        stage.status = 'incomplete'
        stage.summary = '本轮已终结，此阶段记录不完整。'
      }
    }
  }
  return stages
}

/** 提示缺少证据，不将历史缺失当作阶段跳过或业务失败。 */
export function stageEvidenceNotice(events: RunEvent[], turn: CodingTurn): string | null {
  const facts = orderedEvents(events).filter(isStageFact)
  if (facts.length === 0) return turn.status === 'QUEUED' || turn.status === 'RUNNING'
    ? '尚未收到阶段事实。' : '历史记录未提供完整阶段信息。'
  return projectTurnStages(events, turn).some((stage) => stage.status === 'incomplete')
    ? '本轮已终结，阶段记录未完整结束。' : null
}
