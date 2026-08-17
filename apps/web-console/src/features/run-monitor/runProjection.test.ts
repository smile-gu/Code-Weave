import { describe, expect, it } from 'vitest'
import type { RunEvent, RunEventType } from '../../shared/api/types'
import { projectStages, terminalEvent } from './runProjection'

const event = (sequence: number, stage: string, type: RunEventType): RunEvent => ({
  schemaVersion: 1,
  eventId: `event-${sequence}`,
  runId: 'run-1',
  stage,
  attempt: 1,
  sequence,
  timestamp: `2026-08-17T00:00:0${sequence}Z`,
  type,
  payload: {},
})

describe('runProjection', () => {
  it('projects stage lifecycle without guessing later stages', () => {
    const stages = projectStages([
      event(1, 'worktree', 'STAGE_STARTED'),
      event(2, 'worktree', 'STAGE_COMPLETED'),
      event(3, 'coding', 'STAGE_STARTED'),
      event(4, 'coding', 'STAGE_FAILED'),
    ])

    expect(stages.map((stage) => stage.status)).toEqual([
      'completed', 'failed', 'pending', 'pending', 'pending',
    ])
  })

  it('returns the latest terminal run event', () => {
    const failed = event(3, 'run', 'RUN_FAILED')
    expect(terminalEvent([event(1, 'run', 'RUN_CREATED'), failed])).toBe(failed)
  })
})
