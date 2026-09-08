import { act, cleanup, render, screen, fireEvent } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { TurnExecutionPanel } from './TurnExecutionPanel'
import { getCodingTurn } from '../../shared/api/sessionApi'
import type { CodingTurn } from '../../shared/api/types'

vi.mock('../../shared/api/sessionApi', () => ({ getCodingTurn: vi.fn() }))
vi.mock('../run-monitor/StageRail', () => ({ StageRail: () => null }))
vi.mock('../run-monitor/ActivityTimeline', () => ({ ActivityTimeline: () => null }))

class FakeSource extends EventTarget {
 static latest: FakeSource
 onopen: (() => void) | null = null
 onerror: (() => void) | null = null
 constructor() { super(); FakeSource.latest = this }
 close() {}
}
afterEach(() => { cleanup(); vi.useRealTimers(); vi.unstubAllGlobals(); vi.clearAllMocks() })

it('clears unknown-status warning when reconciliation confirms a saved terminal', async () => {
 vi.useFakeTimers()
 vi.stubGlobal('EventSource', FakeSource)
 const running: CodingTurn = {
  turnId: 't', sessionId: 's', sequence: 1, userPrompt: 'test', status: 'RUNNING',
  outcome: null, assistantMessage: null, changedFiles: 0, workspaceFingerprint: null,
  failureCode: null, userMessage: 'running', createdAt: '2026-09-08T00:00:00Z',
  startedAt: '2026-09-08T00:00:01Z', endedAt: null,
 }
 const completed: CodingTurn = { ...running, status: 'COMPLETED', outcome: 'NO_CHANGES', endedAt: '2026-09-08T00:00:02Z' }
 vi.mocked(getCodingTurn).mockResolvedValue(completed)
 const onTerminal = vi.fn()
 const view = render(<TurnExecutionPanel sessionId="s" turn={running} onTerminal={onTerminal} />)
 act(() => FakeSource.latest.onerror?.())
 expect(screen.getByText('连接异常，尚未确认最终状态。')).toBeTruthy()
 await act(async () => { await vi.advanceTimersByTimeAsync(5000) })
 expect(onTerminal).toHaveBeenCalledTimes(1)
 view.rerender(<TurnExecutionPanel sessionId="s" turn={completed} onTerminal={onTerminal} />)
 expect(screen.queryByText('连接异常，尚未确认最终状态。')).toBeNull()
})

const activeTurn: CodingTurn = {
 turnId: 't', sessionId: 's', sequence: 1, userPrompt: 'test', status: 'RUNNING',
 outcome: null, assistantMessage: null, changedFiles: 0, workspaceFingerprint: null,
 failureCode: null, userMessage: 'running', createdAt: '2026-09-08T00:00:00Z',
 startedAt: '2026-09-08T00:00:01Z', endedAt: null,
}
const finishedTurn: CodingTurn = { ...activeTurn, status: 'COMPLETED', outcome: 'NO_CHANGES' }

it('manual confirmation clears warning and stops further reconciliation', async () => {
 vi.useFakeTimers(); vi.stubGlobal('EventSource', FakeSource)
 vi.mocked(getCodingTurn).mockResolvedValue(finishedTurn)
 const onTerminal = vi.fn()
 render(<TurnExecutionPanel sessionId="s" turn={activeTurn} onTerminal={onTerminal} />)
 act(() => FakeSource.latest.onerror?.())
 await act(async () => { fireEvent.click(screen.getByText('手动重试')) })
 expect(screen.queryByRole('status')).toBeNull()
 await act(async () => { await vi.advanceTimersByTimeAsync(15000) })
 expect(onTerminal).toHaveBeenCalledTimes(1)
 expect(getCodingTurn).toHaveBeenCalledTimes(1)
})

it('ignores manual confirmation response after switching turns', async () => {
 vi.useFakeTimers(); vi.stubGlobal('EventSource', FakeSource)
 let resolve!: (turn: CodingTurn) => void
 vi.mocked(getCodingTurn).mockImplementation(() => new Promise(done => { resolve = done }))
 const onTerminal = vi.fn()
 const view = render(<TurnExecutionPanel sessionId="s" turn={activeTurn} onTerminal={onTerminal} />)
 act(() => FakeSource.latest.onerror?.())
 fireEvent.click(screen.getByText('手动重试'))
 view.rerender(<TurnExecutionPanel sessionId="next" turn={{ ...activeTurn, sessionId: 'next', turnId: 'next' }} onTerminal={onTerminal} />)
 await act(async () => { resolve(finishedTurn) })
 expect(onTerminal).not.toHaveBeenCalled()
 expect(screen.queryByRole('status')).toBeNull()
 view.unmount()
 expect(vi.getTimerCount()).toBe(0)
})

it('recovers after bounded confirmation exhaustion and ignores late failures', async () => {
 vi.useFakeTimers(); vi.stubGlobal('EventSource', FakeSource)
 vi.mocked(getCodingTurn).mockResolvedValue(activeTurn)
 const onTerminal = vi.fn()
 render(<TurnExecutionPanel sessionId="s" turn={activeTurn} onTerminal={onTerminal} />)
 act(() => FakeSource.latest.dispatchEvent(new MessageEvent('run-event', {
  data: JSON.stringify({ eventId: 'end', type: 'RUN_COMPLETED', payload: {}, sequence: 1 }),
 })))
 await act(async () => { await vi.advanceTimersByTimeAsync(4000) })
 expect(screen.getByText('尚未确认最终状态，当前任务仍保持占用。')).toBeTruthy()
 vi.mocked(getCodingTurn).mockResolvedValue(finishedTurn)
 await act(async () => { await vi.advanceTimersByTimeAsync(1000) })
 act(() => FakeSource.latest.onerror?.())
 expect(screen.queryByRole('status')).toBeNull()
 expect(onTerminal).toHaveBeenCalledTimes(1)
 expect(vi.getTimerCount()).toBe(0)
})
