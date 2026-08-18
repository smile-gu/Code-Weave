import { request } from './runApi'
import type {
  CodingSession,
  CodingSessionList,
  CreateCodingSessionInput,
} from './types'

/** 创建一个拥有长期 Worktree 的 Coding Session。 */
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
