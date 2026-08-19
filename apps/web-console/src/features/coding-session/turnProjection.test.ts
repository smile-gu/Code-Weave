import { describe, expect, it } from 'vitest'
import type { CodingTurn, RunEvent, RunEventType } from '../../shared/api/types'
import { projectTurnStages } from './turnProjection'

/** 创建供阶段投影测试使用的事实事件。 */
function event(sequence: number, type: RunEventType, payload: Record<string, unknown> = {}, stage = 'coding-turn'): RunEvent {
  return { schemaVersion: 1, eventId: `event-${sequence}`, runId: 'turn-1', stage, attempt: 1, sequence, timestamp: `2026-08-19T00:00:${String(sequence).padStart(2, '0')}Z`, type, payload }
}

/** 创建一个可覆盖结论的 Turn 快照。 */
function turn(overrides: Partial<CodingTurn> = {}): CodingTurn {
  return { turnId: 'turn-1', sessionId: 'session-1', sequence: 1, userPrompt: '修改用户接口', status: 'RUNNING', outcome: null, assistantMessage: null, changedFiles: 0, workspaceFingerprint: null, failureCode: null, userMessage: 'Coding Agent 正在执行。', createdAt: '2026-08-19T00:00:00Z', startedAt: '2026-08-19T00:00:01Z', endedAt: null, ...overrides }
}

describe('projectTurnStages', () => {
  it('aggregates repeated agent events into a fixed five-stage view', () => {
    const stages = projectTurnStages([
      event(1, 'RUN_STARTED'),
      event(2, 'AGENT_EVENT_OBSERVED', { eventType: 'TOOL_CALL_STARTED', payload: { name: 'Edit', input: { file_path: 'src/User.java' } } }),
      event(3, 'AGENT_EVENT_OBSERVED', { eventType: 'TOOL_CALL_COMPLETED', payload: {} }),
    ], turn())

    expect(stages).toHaveLength(5)
    expect(stages.map((stage) => stage.status)).toEqual(['completed', 'running', 'pending', 'pending', 'pending'])
    expect(stages[1].summary).toBe('工具调用已成功返回。')
  })

  it('maps a policy violation to the path gate instead of Maven', () => {
    const stages = projectTurnStages([], turn({ status: 'COMPLETED', outcome: 'POLICY_VIOLATION', changedFiles: 2, endedAt: '2026-08-19T00:00:08Z' }))

    expect(stages.map((stage) => stage.status)).toEqual(['completed', 'completed', 'completed', 'failed', 'pending'])
    expect(stages[3].summary).toContain('超出 Session 允许路径')
  })

  it('maps Maven verification failure to the final stage', () => {
    const stages = projectTurnStages([], turn({ status: 'COMPLETED', outcome: 'VERIFICATION_FAILED', changedFiles: 1, endedAt: '2026-08-19T00:00:08Z' }))

    expect(stages.map((stage) => stage.status)).toEqual(['completed', 'completed', 'completed', 'completed', 'failed'])
  })
})
