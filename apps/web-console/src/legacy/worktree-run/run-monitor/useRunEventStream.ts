import { useEffect, useMemo, useRef, useState } from 'react'
import type { RunEvent } from '../../../shared/api/types'
import { terminalEvent } from '../../../features/run-monitor/runProjection'

export type StreamState = 'connecting' | 'open' | 'reconnecting' | 'completed' | 'failed'

/** 订阅 Run SSE，并按 sequence 去重保存事件。 */
export function useRunEventStream(runId: string, enabled = true) {
  const [events, setEvents] = useState<RunEvent[]>([])
  const [streamState, setStreamState] = useState<StreamState>('connecting')
  const cursor = useRef(0)

  useEffect(() => {
    if (!enabled) return
    let disposed = false
    const source = new EventSource(
      `/api/v1/legacy/worktree-runs/${encodeURIComponent(runId)}/stream?afterSequence=${cursor.current}`,
    )

    source.onopen = () => {
      if (!disposed) setStreamState('open')
    }
    source.onerror = () => {
      if (!disposed) setStreamState('reconnecting')
    }

    const receive = (raw: Event) => {
      if (disposed) return
      try {
        const event = JSON.parse((raw as MessageEvent<string>).data) as RunEvent
        if (event.runId !== runId || event.sequence <= cursor.current) return
        cursor.current = event.sequence
        setEvents((current) => [...current, event])
        if (['RUN_COMPLETED', 'RUN_FAILED', 'RUN_CANCELLED'].includes(event.type)) {
          setStreamState('completed')
          source.close()
        }
      } catch {
        setStreamState('failed')
        source.close()
      }
    }

    source.addEventListener('run-event', receive)
    return () => {
      disposed = true
      source.removeEventListener('run-event', receive)
      source.close()
    }
  }, [enabled, runId])

  return {
    events,
    streamState,
    lastSequence: cursor.current,
    terminal: useMemo(() => terminalEvent(events), [events]),
  }
}
