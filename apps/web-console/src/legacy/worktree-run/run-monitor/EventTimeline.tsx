import { useMemo, useState } from 'react'
import type { RunEvent } from '../../../shared/api/types'
import { ActivityTimeline } from '../../../features/run-monitor/ActivityTimeline'
import { projectActivities, projectProcessOutput } from '../../../features/run-monitor/activityProjection'
import { ProcessConsole } from './ProcessConsole'
import { RawEventTimeline } from './RawEventTimeline'

type EventView = 'activities' | 'raw'

/** 在用户活动和原始事实事件之间提供分层查看。 */
export function EventTimeline({ events }: { events: RunEvent[] }) {
  const [view, setView] = useState<EventView>('activities')
  const activities = useMemo(() => projectActivities(events), [events])
  const processOutput = useMemo(() => projectProcessOutput(events), [events])

  return (
    <div className="event-workspace">
      <div className="event-view-switch" role="group" aria-label="事件展示方式">
        <button className={view === 'activities' ? 'active' : ''} onClick={() => setView('activities')}>
          执行动态 <span>{activities.length}</span>
        </button>
        <button className={view === 'raw' ? 'active' : ''} onClick={() => setView('raw')}>
          原始事件 <span>{events.length}</span>
        </button>
      </div>
      {view === 'activities' && (
        <>
          <ActivityTimeline activities={activities} />
          <ProcessConsole output={processOutput} />
        </>
      )}
      {view === 'raw' && <RawEventTimeline events={events} />}
    </div>
  )
}
