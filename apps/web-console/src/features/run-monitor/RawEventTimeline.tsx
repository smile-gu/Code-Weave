import { useMemo } from 'react'
import { TerminalSquare } from 'lucide-react'
import type { RunEvent } from '../../shared/api/types'
import {
  createEventPresentationContext, eventSummary, eventTitle,
} from './runProjection'

const time = new Intl.DateTimeFormat('zh-CN', {
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
})

/** 按事实序号展示未经聚合的原始 Run 事件。 */
export function RawEventTimeline({ events }: { events: RunEvent[] }) {
  const context = useMemo(() => createEventPresentationContext(events), [events])
  if (events.length === 0) {
    return <div className="empty-state">等待第一条运行事件…</div>
  }

  return (
    <ol className="event-list">
      {events.map((event) => (
        <li key={event.eventId}>
          <span className="event-sequence">#{event.sequence}</span>
          <span className="event-dot"><TerminalSquare size={13} /></span>
          <EventContent event={event} context={context} />
        </li>
      ))}
    </ol>
  )
}

/** 按需展示事件摘要，避免无新增信息时重复标题。 */
function EventContent({
  event,
  context,
}: {
  event: RunEvent
  context: ReturnType<typeof createEventPresentationContext>
}) {
  const summary = eventSummary(event, context)
  return (
    <div>
      <div className="event-title">
        <strong>{eventTitle(event, context)}</strong>
        <time>{time.format(new Date(event.timestamp))}</time>
      </div>
      {summary && <p>{summary}</p>}
      <small>{event.type} · {event.stage} · 第 {event.attempt} 次尝试</small>
    </div>
  )
}
