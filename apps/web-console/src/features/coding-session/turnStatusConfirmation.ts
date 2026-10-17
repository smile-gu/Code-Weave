import type { CodingTurn } from '../../shared/api/types'

export const TURN_STATUS_RECONCILIATION_MS = 5_000
export const TERMINAL_CONFIRMATION_DELAYS_MS = [0, 250, 500, 1_000, 2_000] as const

export type TerminalConfirmation =
  | { kind: 'confirmed'; turn: CodingTurn }
  | { kind: 'unconfirmed'; error: Error | null }
  | { kind: 'stale'; error: null }

/** 判断持久化 Turn 是否已经进入不可变终态。 */
export function isTerminalTurn(turn: CodingTurn) {
  return !['QUEUED', 'RUNNING'].includes(turn.status)
}

/** 以有限退避查询单 Turn 状态，不把网络或确认失败伪造成业务失败。 */
export async function confirmTerminalTurn(
  load: () => Promise<CodingTurn>,
  isCurrent: () => boolean,
  wait: (milliseconds: number) => Promise<void> = (milliseconds) => new Promise(
    (resolve) => window.setTimeout(resolve, milliseconds),
  ),
): Promise<TerminalConfirmation> {
  let lastError: Error | null = null
  for (const delay of TERMINAL_CONFIRMATION_DELAYS_MS) {
    if (!isCurrent()) return { kind: 'stale', error: null }
    if (delay > 0) await wait(delay)
    if (!isCurrent()) return { kind: 'stale', error: null }
    try {
      const turn = await load()
      if (!isCurrent()) return { kind: 'stale', error: null }
      if (isTerminalTurn(turn)) return { kind: 'confirmed', turn }
      lastError = null
    } catch (error) {
      lastError = error instanceof Error ? error : new Error('终态确认请求失败')
    }
  }
  return { kind: 'unconfirmed', error: lastError }
}
