import { describe, expect, it } from 'vitest'
import type { RunEvent } from '../api/types'
import { readAgentEvent, orderedEvents } from './eventProtocol'
import { projectActivities } from '../../features/run-monitor/activityProjection'

/** 创建用于历史兼容及活动关联的事件。 */
function event(sequence: number, payload: Record<string, unknown>, runId = 'turn-1'): RunEvent {
  return { schemaVersion: 1, eventId: runId + '-' + sequence, runId, sequence, attempt: 1,
    stage: 'coding', type: 'AGENT_EVENT_OBSERVED', timestamp: '2026-09-08T00:00:00Z', payload }
}

describe('event protocol boundary', () => {
  it.each(['eventType', 'agentEventType'])('reads legacy %s without fabricated metadata', (key) => {
    const normalized = readAgentEvent(event(1, { [key]: 'MESSAGE_COMPLETED', payload: { text: '你好' } }))
    expect(normalized.type).toBe('MESSAGE_COMPLETED')
    expect(normalized.payload.text).toBe('你好')
    expect(normalized.externalId).toBeNull()
    expect(normalized.payloadBytes).toBeNull()
    expect(normalized.payloadSha256).toBeNull()
  })

  it('preserves original correlation IDs and scopes by turn', () => {
    const payload = { agentProtocolVersion: 1, agentEventType: 'TOOL_CALL_STARTED', externalId: ' x ' }
    expect(readAgentEvent(event(1, payload)).externalId).toBe(' x ')
    expect(readAgentEvent(event(1, payload)).correlationKey)
      .not.toBe(readAgentEvent(event(1, payload, 'turn-2')).correlationKey)
  })

  it('never parses truncated previews or uses hidden full payload', () => {
    const normalized = readAgentEvent(event(1, { agentProtocolVersion: 1, agentEventType: 'MESSAGE_COMPLETED',
      payloadTruncated: true, payloadPreview: '{"text":"中', payload: { text: 'must not use' } }))
    expect(normalized.payload).toEqual({})
    expect(normalized.preview).toBe('{"text":"中')
    expect(projectActivities([event(1, { agentProtocolVersion: 1, agentEventType: 'MESSAGE_COMPLETED',
      payloadTruncated: true, payloadPreview: '{"text":"中' })]).at(-1)?.summary).toBe('{"text":"中')
  })

  it('degrades unsupported versions and malformed envelopes', () => {
    expect(readAgentEvent(event(1, { agentProtocolVersion: 99, agentEventType: 'TOOL_CALL_STARTED' })).type).toBeNull()
    expect(orderedEvents([event(1, null as unknown as Record<string, unknown>)])[0].payload).toEqual({})
    expect(readAgentEvent(event(1, { agentProtocolVersion: 1, eventType: 'TOOL_CALL_STARTED' })).type).toBeNull()
    expect(readAgentEvent(event(1, { externalId: '' })).correlationKey).toBeNull()
  })

  it('sorts replay and does not duplicate activities', () => {
    const start = event(1, { agentEventType: 'TOOL_CALL_STARTED', externalId: 'tool',
      payload: { name: 'Read', input: { file_path: 'a.java' } } })
    const end = event(2, { agentEventType: 'TOOL_CALL_COMPLETED', externalId: 'tool', payload: {} })
    const activities = projectActivities([end, start, end])
    expect(activities.filter((a) => a.id.startsWith('agent:tool:'))).toHaveLength(1)
    expect(activities.at(-1)?.sourceSequences).toEqual([1, 2])
  })

  it('does not pair tools without external IDs in new protocol', () => {
    const activities = projectActivities([
      event(1, { agentProtocolVersion: 1, agentEventType: 'TOOL_CALL_STARTED', payload: { id: 'not-external' } }),
      event(2, { agentProtocolVersion: 1, agentEventType: 'TOOL_CALL_COMPLETED', payload: { id: 'not-external' } }),
    ])
    expect(activities.filter((a) => a.id.startsWith('agent:tool:'))).toHaveLength(2)
  })

  it('keeps formal stage facts separate from agent and report activities', () => {
    const start: RunEvent = { ...event(1, { stageProtocolVersion: 1, message: '开始' }), type: 'STAGE_STARTED' }
    const complete = event(2, { agentProtocolVersion: 1, agentEventType: 'RUNTIME_COMPLETED', payload: {} })
    const maven: RunEvent = { ...event(3, { status: 'PASSED' }), stage: 'maven', type: 'ANALYSIS_COMPLETED' }
    const activities = projectActivities([start, complete, maven])
    expect(activities.find((a) => a.id === 'stage:coding:1')?.status).toBe('running')
    expect(activities.find((a) => a.id === 'stage:maven:1')).toBeUndefined()
    expect(activities.find((a) => a.id === 'verification:process:1')?.title).toBe('Maven 报告分析结束')
  })

  it('does not reopen terminal stage activity after a late start', () => {
    const end: RunEvent = { ...event(1, { stageProtocolVersion: 1, message: '完成' }), type: 'STAGE_COMPLETED' }
    const start: RunEvent = { ...event(2, { stageProtocolVersion: 1, message: '开始' }), type: 'STAGE_STARTED' }
    expect(projectActivities([start, end])[0].status).toBe('success')
  })
})
