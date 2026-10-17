import type { RunEvent } from '../api/types'

/** 只在读取边界兼容旧协议，不修改磁盘事件或伪造缺失字段。 */
export function readAgentEvent(event: RunEvent) {
  const envelope = object(event.payload)
  const version = envelope.agentProtocolVersion
  const supported = version === undefined || version === 1
  const payload = envelope.payloadTruncated === true ? {} : object(envelope.payload)
  const type = supported ? string(version === undefined
    ? envelope.agentEventType ?? envelope.eventType : envelope.agentEventType) : null
  const externalId = string(envelope.externalId)
    ?? (version === undefined ? string(payload.id) ?? string(payload.tool_use_id) : null)
  return {
    supported, type, externalId, payload,
    correlationKey: externalId === null || !externalId.trim() ? null : JSON.stringify([event.runId, externalId]),
    payloadBytes: typeof envelope.payloadBytes === 'number' ? envelope.payloadBytes : null,
    payloadSha256: string(envelope.payloadSha256),
    truncated: envelope.payloadTruncated === true,
    preview: string(envelope.payloadPreview),
  }
}

export const codingStageIds = ['workspace', 'coding', 'change-collection', 'change-policy', 'maven'] as const
export type CodingStageId = typeof codingStageIds[number]

/** 仅接受版本、阶段、次数和必要载荷均有效的阶段事实。 */
export function isStageFact(event: RunEvent): boolean {
  const payload = object(event.payload)
  if (payload.stageProtocolVersion !== 1 || event.attempt !== 1
    || !codingStageIds.includes(event.stage as CodingStageId)
    || !string(payload.message)?.trim()) return false
  if (event.type === 'STAGE_STARTED' || event.type === 'STAGE_COMPLETED') return true
  if (event.type === 'STAGE_FAILED') return !!string(payload.failureCode)?.trim() && !!string(payload.failureKind)?.trim()
  if (event.type === 'STAGE_SKIPPED') return !!string(payload.reasonCode)?.trim()
  return false
}

/** 去重并按事实序号重放，坏信封不进入产品投影。 */
export function orderedEvents(events: RunEvent[]): RunEvent[] {
  const unique = new Map<string, RunEvent>()
  for (const event of events) {
    if (!event || typeof event.eventId !== 'string' || typeof event.runId !== 'string'
      || typeof event.type !== 'string' || !Number.isSafeInteger(event.sequence)
      || !Number.isFinite(Date.parse(event.timestamp))) continue
    if (!unique.has(event.eventId)) unique.set(event.eventId, { ...event, payload: object(event.payload) })
  }
  return [...unique.values()].sort((a, b) => a.sequence - b.sequence)
}

/** 安全读取对象载荷，文本预览不作为 JSON 解析。 */
function object(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}
}

/** 原样读取字符串关联信息。 */
function string(value: unknown): string | null { return typeof value === 'string' ? value : null }
