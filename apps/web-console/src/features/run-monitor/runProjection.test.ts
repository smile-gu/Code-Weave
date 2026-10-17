import { describe, expect, it } from 'vitest'
import type { RunEvent, RunEventType } from '../../shared/api/types'
import {
  createEventPresentationContext, eventSummary, eventTitle, projectStages, terminalEvent,
} from './runProjection'

const event = (
  sequence: number,
  stage: string,
  type: RunEventType,
  payload: Record<string, unknown> = {},
): RunEvent => ({
  schemaVersion: 1,
  eventId: `event-${sequence}`,
  runId: 'run-1',
  stage,
  attempt: 1,
  sequence,
  timestamp: `2026-08-17T00:00:0${sequence}Z`,
  type,
  payload,
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

  it('maps agent wrapper events to specific Chinese actions', () => {
    const toolStarted = event(1, 'coding', 'AGENT_EVENT_OBSERVED', {
      agentEventType: 'TOOL_CALL_STARTED',
      externalId: 'call-1',
      payload: { name: 'Edit', input: { file_path: 'src/main/java/demo/Greeting.java' } },
    })
    const toolCompleted = event(2, 'coding', 'AGENT_EVENT_OBSERVED', {
      agentEventType: 'TOOL_CALL_COMPLETED',
      externalId: 'call-1',
      payload: { content: 'updated' },
    })
    const context = createEventPresentationContext([toolStarted, toolCompleted])

    expect(eventTitle(toolStarted, context)).toBe('Agent 发起工具调用 · Edit')
    expect(eventSummary(toolStarted, context)).toBe('Edit：src/main/java/demo/Greeting.java')
    expect(eventTitle(toolCompleted, context)).toBe('Agent 工具调用完成 · Edit')
    expect(eventSummary(toolCompleted, context)).toBe('Edit 工具已成功返回。')
  })

  it('distinguishes repeated process output by stage and byte range', () => {
    const output = event(1, 'maven', 'PROCESS_STDOUT', {
      fromByte: 0,
      toByte: 33,
      bytes: 33,
      preview: '[INFO] Scanning for projects...',
    })

    expect(eventTitle(output)).toBe('Maven 验证 · 标准输出（0–33 字节）')
    expect(eventSummary(output)).toBe('[INFO] Scanning for projects...')
  })

  it('omits summaries that only repeat a complete lifecycle title', () => {
    expect(eventSummary(event(1, 'run', 'RUN_QUEUED', {
      message: '任务已进入执行队列。',
    }))).toBeUndefined()
    expect(eventSummary(event(2, 'run', 'RUN_STARTED', {
      message: '任务开始执行。',
    }))).toBeUndefined()
  })

  it('uses stage outputs as additional information instead of repeating titles', () => {
    const started = event(1, 'worktree', 'STAGE_STARTED', { baseRef: 'HEAD' })
    const completed = event(2, 'worktree', 'STAGE_COMPLETED', {
      resolvedCommit: '41906e2d68bfe9a79929bb37b2f3c4b274e5ad8d',
    })

    expect(eventSummary(started)).toBe('正在基于 HEAD 创建独立 Git Worktree。')
    expect(eventSummary(completed)).toBe('隔离 Worktree 已创建，基准提交为 41906e2d68bf。')
  })

  it('keeps unknown agent protocol names visible for troubleshooting', () => {
    const futureEvent = event(1, 'coding', 'AGENT_EVENT_OBSERVED', {
      agentEventType: 'FUTURE_AGENT_EVENT',
      payload: {},
    })

    expect(eventTitle(futureEvent)).toBe('Agent 事件 · FUTURE_AGENT_EVENT')
    expect(eventSummary(futureEvent)).toBeUndefined()
  })
})
