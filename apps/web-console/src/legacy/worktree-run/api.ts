import { request } from '../../shared/api/http'
import type {
  CreateRunInput,
  CreateRunResponse,
  RunResult,
  RunStatus,
} from './types'
import type { RunChanges } from '../../shared/api/types'

/** 提交一次新的 旧版 Worktree Run。 */
export function createRun(input: CreateRunInput) {
  return request<CreateRunResponse>('/api/v1/legacy/worktree-runs', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  })
}

/** 查询 Run 的当前状态投影。 */
export function getRunStatus(runId: string) {
  return request<RunStatus>(`/api/v1/legacy/worktree-runs/${runId}`)
}

/** 查询 Run 的最终业务结果。 */
export function getRunResult(runId: string) {
  return request<RunResult>(`/api/v1/legacy/worktree-runs/${runId}/result`)
}

/** 查询 Run 已采集的代码变更。 */
export function getRunChanges(runId: string) {
  return request<RunChanges>(`/api/v1/legacy/worktree-runs/${runId}/changes`)
}
