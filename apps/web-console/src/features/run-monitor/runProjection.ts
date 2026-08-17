import type { RunEvent, RunEventType } from '../../shared/api/types'

export type StageKey = 'worktree' | 'coding' | 'change-collection' | 'change-policy' | 'maven'
export type StageStatus = 'pending' | 'running' | 'completed' | 'failed'

export type StageView = {
  key: StageKey
  label: string
  description: string
  status: StageStatus
  startedAt: string | null
  endedAt: string | null
}

const stageDefinitions: Omit<StageView, 'status' | 'startedAt' | 'endedAt'>[] = [
  { key: 'worktree', label: '隔离工作区', description: '创建独立 Git Worktree' },
  { key: 'coding', label: 'Claude Code', description: '在受限目录内修改代码' },
  { key: 'change-collection', label: '变更采集', description: '生成文件清单与 Unified Diff' },
  { key: 'change-policy', label: '路径门禁', description: '确认改动未越过任务边界' },
  { key: 'maven', label: 'Maven 验证', description: '独立执行编译与测试' },
]

const terminalTypes = new Set<RunEventType>(['RUN_COMPLETED', 'RUN_FAILED', 'RUN_CANCELLED'])

/** 根据阶段事实事件生成五阶段只读状态。 */
export function projectStages(events: RunEvent[]): StageView[] {
  const stages: StageView[] = stageDefinitions.map((stage) => ({
    ...stage,
    status: 'pending' as StageStatus,
    startedAt: null,
    endedAt: null,
  }))
  const byKey = new Map(stages.map((stage) => [stage.key, stage]))

  for (const event of events) {
    const stage = byKey.get(event.stage as StageKey)
    if (!stage) continue
    if (event.type === 'STAGE_STARTED') {
      stage.status = 'running'
      stage.startedAt = event.timestamp
    } else if (event.type === 'STAGE_COMPLETED') {
      stage.status = 'completed'
      stage.endedAt = event.timestamp
    } else if (event.type === 'STAGE_FAILED') {
      stage.status = 'failed'
      stage.endedAt = event.timestamp
    }
  }
  return stages
}

/** 返回事件列表中最后一条 Run 终态事件。 */
export function terminalEvent(events: RunEvent[]) {
  return events.findLast((event) => terminalTypes.has(event.type)) ?? null
}

/** 将事件载荷中的用户信息提取为可读摘要。 */
export function eventSummary(event: RunEvent): string {
  const payload = event.payload
  const message = payload.message
  if (typeof message === 'string' && message.trim()) return message
  const code = payload.code
  if (typeof code === 'string' && code.trim()) return code
  const status = payload.status
  if (typeof status === 'string' && status.trim()) return status
  const reason = payload.reason
  if (typeof reason === 'string' && reason.trim()) return reason
  return event.type.replaceAll('_', ' ').toLowerCase()
}
