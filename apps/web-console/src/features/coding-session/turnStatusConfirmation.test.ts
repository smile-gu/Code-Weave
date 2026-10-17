import { describe, expect, it, vi } from 'vitest'
import type { CodingTurn } from '../../shared/api/types'
import { confirmTerminalTurn, TERMINAL_CONFIRMATION_DELAYS_MS } from './turnStatusConfirmation'

const running: CodingTurn = {
  turnId: 'turn-1', sessionId: 'session-1', sequence: 1,
  userPrompt: '继续开发', status: 'RUNNING', outcome: null,
  assistantMessage: null, changedFiles: 0, workspaceFingerprint: null,
  failureCode: null, userMessage: '正在执行', createdAt: '2026-09-08T00:00:00Z',
  startedAt: '2026-09-08T00:00:01Z', endedAt: null,
}

describe('confirmTerminalTurn', () => {
  it('retries a terminal notification until persisted state becomes terminal', async () => {
    const load = vi.fn()
      .mockResolvedValueOnce(running)
      .mockResolvedValueOnce({ ...running, status: 'COMPLETED', endedAt: '2026-09-08T00:00:02Z' })
    const wait = vi.fn().mockResolvedValue(undefined)

    const result = await confirmTerminalTurn(load, () => true, wait)

    expect(result.kind).toBe('confirmed')
    expect(load).toHaveBeenCalledTimes(2)
    expect(wait).toHaveBeenCalledWith(250)
  })

  it('keeps an unconfirmed state after bounded network failures', async () => {
    const load = vi.fn().mockRejectedValue(new Error('offline'))

    const result = await confirmTerminalTurn(load, () => true, async () => undefined)

    expect(result.kind).toBe('unconfirmed')
    expect(load).toHaveBeenCalledTimes(TERMINAL_CONFIRMATION_DELAYS_MS.length)
    expect(result.kind === 'unconfirmed' && result.error?.message).toBe('offline')
  })

  it('ignores a response after the user switches away from the turn', async () => {
    let current = true
    const load = vi.fn().mockImplementation(async () => {
      current = false
      return { ...running, status: 'COMPLETED', endedAt: '2026-09-08T00:00:02Z' }
    })

    const result = await confirmTerminalTurn(load, () => current, async () => undefined)

    expect(result.kind).toBe('stale')
  })
})
