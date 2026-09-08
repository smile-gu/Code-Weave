import { request } from './http'
import type {
  CodingSession,
  CodingSessionList,
  CreateCodingSessionInput,
  CodingTurnList,
  CodingTurn,
  RunEvent,
  RunChanges,
  WorkspaceChanges,
  ReviewBaselineReadiness,
} from './types'

/** 创建一个直接绑定真实 Git 工作区的 Coding Session。 */
export function createCodingSession(input: CreateCodingSessionInput) {
  return request<CodingSession>('/api/v1/coding-sessions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  })
}

/** 查询最近更新的 Coding Session。 */
export function getRecentCodingSessions(limit = 20) {
  return request<CodingSessionList>(`/api/v1/coding-sessions?limit=${limit}`)
}

/** 查询指定 Coding Session 的持久详情。 */
export function getCodingSession(sessionId: string) {
  return request<CodingSession>(`/api/v1/coding-sessions/${sessionId}`)
}

/** 查询持续 Session 的多轮 Coding 历史。 */
export function getCodingTurns(sessionId: string) { return request<CodingTurnList>(`/api/v1/coding-sessions/${sessionId}/turns`) }

/** 只查询单个 Turn 的持久状态，供终态确认和低频对账使用。 */
export function getCodingTurn(sessionId: string, turnId: string, signal?: AbortSignal) {
  return request<CodingTurn>(`/api/v1/coding-sessions/${sessionId}/turns/${turnId}`, { signal })
}

/** 一次性读取历史阶段事实，不建立历史 SSE 或轮询事件。 */
export function getCodingTurnEvents(sessionId: string, turnId: string, signal?: AbortSignal) {
  return request<RunEvent[]>(`/api/v1/coding-sessions/${sessionId}/turns/${turnId}/events`, { signal })
}

/** 提交下一轮 Coding 指令。 */
export function submitCodingTurn(sessionId: string, prompt: string) {
  return request<CodingTurn>(`/api/v1/coding-sessions/${sessionId}/turns`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ prompt }) })
}

/** 停止一轮正在执行的 Coding。 */
export function cancelCodingTurn(sessionId: string, turnId: string) { return request<CodingTurn>(`/api/v1/coding-sessions/${sessionId}/turns/${turnId}/cancel`, { method: 'POST' }) }

/** 读取真实工作区相对 Session 创建基线的累计代码变更。 */
export function getCodingTurnChanges(sessionId: string, turnId: string) {
  return request<RunChanges>(`/api/v1/coding-sessions/${sessionId}/turns/${turnId}/changes`)
}

/** 读取当前 Session 相对活动审查基线的累计代码变更。 */
export function getCodingSessionChanges(sessionId: string) {
  return request<WorkspaceChanges>(`/api/v1/coding-sessions/${sessionId}/changes`)
}

/** 检查当前模块的正式 Git 提交是否可成为下一模块的活动审查基线。 */
export function getReviewBaselineReadiness(sessionId: string) {
  return request<ReviewBaselineReadiness>(`/api/v1/coding-sessions/${sessionId}/review-baseline-readiness`)
}

/** 用户确认模块已完成 Review 与自测后推进活动审查基线。 */
export function advanceReviewBaseline(sessionId: string, input: {
  expectedActiveBaselineCommit: string
  expectedHead: string
  workspaceFingerprint: string
}) {
  return request<CodingSession>(`/api/v1/coding-sessions/${sessionId}/review-baseline/advance`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  })
}
