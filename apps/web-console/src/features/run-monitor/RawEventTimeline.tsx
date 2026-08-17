import { TerminalSquare } from 'lucide-react'
import type { RunEvent } from '../../shared/api/types'
import { eventSummary } from './runProjection'

const time = new Intl.DateTimeFormat('zh-CN', {
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
})

/** 按事实序号展示未经聚合的原始 Run 事件。 */
export function RawEventTimeline({ events }: { events: RunEvent[] }) {
  if (events.length === 0) {
    return <div className="empty-state">等待第一条运行事件…</div>
  }

  return (
    <ol className="event-list">
      {events.map((event) => (
        <li key={event.eventId}>
          <span className="event-sequence">#{event.sequence}</span>
          <span className="event-dot"><TerminalSquare size={13} /></span>
          <div>
            <div className="event-title">
              <strong>{event.type}</strong>
              <time>{time.format(new Date(event.timestamp))}</time>
            </div>
            <p>{eventSummary(event)}</p>
            <small>{event.stage} · attempt {event.attempt}</small>
          </div>
        </li>
      ))}
    </ol>
  )
}
