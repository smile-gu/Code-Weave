import { request } from './runApi'
import type {
  CodingSession,
  CodingSessionList,
  CreateCodingSessionInput,
  CodingTurnList,
  CodingTurn,
  MergeReadiness,
  RunChanges,
  CodingSessionIntegration,
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

/** 读取当前 Worktree 的合并门禁。 */
export function getMergeReadiness(sessionId: string) { return request<MergeReadiness>(`/api/v1/coding-sessions/${sessionId}/merge-readiness`) }

/** 用户确认后将 Worktree 以 Fast-forward 写回源码仓库。 */
export function mergeCodingSession(sessionId: string, input: { commitMessage: string; expectedSourceHead: string; expectedTargetBranch: string; workspaceFingerprint: string }) {
  return request(`/api/v1/coding-sessions/${sessionId}/merge`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(input) })
}

/** 查询最近一次写回原仓库的持久状态。 */
export async function getCodingSessionIntegration(sessionId: string) {
  const response = await fetch(`/api/v1/coding-sessions/${sessionId}/integration`)
  if (response.status === 204) return null
  if (!response.ok) {
    const body = await response.json() as { message?: string }
    throw new Error(body.message ?? `请求失败：HTTP ${response.status}`)
  }
  return response.json() as Promise<CodingSessionIntegration>
}
