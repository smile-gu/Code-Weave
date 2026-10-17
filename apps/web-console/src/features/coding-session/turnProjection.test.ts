import { describe, expect, it } from 'vitest'
import type { CodingTurn, RunEvent, RunEventType } from '../../shared/api/types'
import { projectTurnStages, stageEvidenceNotice } from './turnProjection'

/** 创建供阶段投影测试使用的事实事件。 */
function event(sequence: number, type: RunEventType, payload: Record<string, unknown> = {}, stage = 'coding-turn'): RunEvent {
  return { schemaVersion: 1, eventId: `event-${sequence}`, runId: 'turn-1', stage, attempt: 1, sequence, timestamp: `2026-08-19T00:00:${String(sequence).padStart(2, '0')}Z`, type, payload }
}

/** 创建一个可覆盖结论的 Turn 快照。 */
function turn(overrides: Partial<CodingTurn> = {}): CodingTurn {
  return { turnId: 'turn-1', sessionId: 'session-1', sequence: 1, userPrompt: '修改用户接口', status: 'RUNNING', outcome: null, assistantMessage: null, changedFiles: 0, workspaceFingerprint: null, failureCode: null, userMessage: 'Coding Agent 正在执行。', createdAt: '2026-08-19T00:00:00Z', startedAt: '2026-08-19T00:00:01Z', endedAt: null, ...overrides }
}

/** 创建携带版本的正式阶段事实。 */
function fact(sequence: number, stage: string, type: RunEventType, payload: Record<string, unknown> = {}) {
  return event(sequence, type, { stageProtocolVersion: 1, message: '阶段说明', ...payload }, stage)
}

describe('projectTurnStages', () => {
  it('never infers stages from run or agent events', () => {
    const stages = projectTurnStages([
      event(1, 'RUN_STARTED'),
      event(2, 'AGENT_EVENT_OBSERVED', { eventType: 'RUNTIME_COMPLETED', payload: {} }),
    ], turn())
    expect(stages.map((s) => s.status)).toEqual(Array(5).fill('pending'))
  })

  it('projects all five stages in sequence', () => {
    const stages = ['workspace', 'coding', 'change-collection', 'change-policy', 'maven']
    const facts = stages.flatMap((stage, index) => [
      fact(index * 2 + 1, stage, 'STAGE_STARTED'), fact(index * 2 + 2, stage, 'STAGE_COMPLETED'),
    ])
    expect(projectTurnStages(facts, turn()).map((s) => s.status)).toEqual(Array(5).fill('completed'))
  })

  it('keeps missing historical stage evidence explicit', () => {
    const historical = turn({ status: 'COMPLETED', outcome: 'CHANGES_VERIFIED' })
    expect(projectTurnStages([], historical).map((s) => s.status)).toEqual(Array(5).fill('incomplete'))
    expect(stageEvidenceNotice([], historical)).toContain('历史记录未提供完整阶段信息')
  })

  it('records no-changes skips without fabricating completions', () => {
    const stages = projectTurnStages([
      fact(1, 'change-collection', 'STAGE_COMPLETED'),
      fact(2, 'change-policy', 'STAGE_SKIPPED', { reasonCode: 'NO_CHANGES' }),
      fact(3, 'maven', 'STAGE_SKIPPED', { reasonCode: 'NO_CHANGES' }),
    ], turn())
    expect(stages.slice(2).map((s) => s.status)).toEqual(['completed', 'skipped', 'skipped'])
  })

  it.each([
    ['change-policy', 'POLICY', 'CHANGE_POLICY_REJECTED', 'failed'],
    ['maven', 'WORKSPACE_DRIFT', 'WORKSPACE_CHANGED_DURING_VERIFICATION', 'failed'],
    ['coding', 'CANCELLED', 'CANCELLED', 'stopped'],
  ])('projects %s failure using its evidence', (stage, failureKind, failureCode, status) => {
    const stages = projectTurnStages([fact(1, stage, 'STAGE_FAILED', { failureKind, failureCode })], turn())
    expect(stages.find((s) => s.key === stage)?.status).toBe(status)
    expect(stages.filter((s) => s.status !== 'pending')).toHaveLength(1)
  })

  it('deduplicates sorts and ignores late starts or conflicting endings', () => {
    const start = fact(1, 'coding', 'STAGE_STARTED')
    const end = fact(2, 'coding', 'STAGE_COMPLETED')
    const events = [end, start, end, fact(3, 'coding', 'STAGE_STARTED'),
      fact(4, 'coding', 'STAGE_FAILED', { failureCode: 'X', failureKind: 'AGENT' })]
    expect(projectTurnStages(events, turn())[1].status).toBe('completed')
    expect(projectTurnStages(events, turn())[1].endedAt).toBe(end.timestamp)
  })

  it('rejects skip on an active stage and unsupported protocols', () => {
    const events = [fact(1, 'coding', 'STAGE_STARTED'),
      fact(2, 'coding', 'STAGE_SKIPPED', { reasonCode: 'NO_CHANGES' }),
      fact(3, 'maven', 'STAGE_COMPLETED', { stageProtocolVersion: 9 }),
      fact(4, 'future-stage', 'STAGE_COMPLETED'),
      fact(5, 'change-policy', 'STAGE_FAILED')]
    expect(projectTurnStages(events, turn()).map((s) => s.status))
      .toEqual(['pending', 'running', 'pending', 'pending', 'pending'])
  })

  it('stops animation on terminal without fabricating failure', () => {
    const events = [fact(1, 'coding', 'STAGE_STARTED')]
    const interrupted = turn({ status: 'INTERRUPTED' })
    expect(projectTurnStages(events, interrupted)[1].status).toBe('incomplete')
    expect(stageEvidenceNotice(events, interrupted)).toContain('未完整结束')
  })

  it('keeps completed stages even when final cancellation wins', () => {
    const events = ['workspace', 'coding', 'change-collection', 'change-policy', 'maven']
      .map((stage, index) => fact(index + 1, stage, 'STAGE_COMPLETED'))
    expect(projectTurnStages(events, turn({ status: 'CANCELLED' })).map((s) => s.status))
      .toEqual(Array(5).fill('completed'))
  })
})
