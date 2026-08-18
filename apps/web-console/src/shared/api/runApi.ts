import type {
  ApiErrorBody,
  CreateRunInput,
  CreateRunResponse,
  RunChanges,
  RunResult,
  RunStatus,
  RuntimeReadiness,
} from './types'

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly fieldErrors: Record<string, string>

  constructor(body: ApiErrorBody) {
    super(body.message)
    this.name = 'ApiError'
    this.status = body.status
    this.code = body.code
    this.fieldErrors = body.fieldErrors ?? {}
  }
}

/** 请求 JSON 接口并将稳定错误响应转换为前端异常。 */
async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: {
      Accept: 'application/json',
      ...init?.headers,
    },
  })
  if (!response.ok) {
    const fallback: ApiErrorBody = {
      timestamp: new Date().toISOString(),
      status: response.status,
      code: 'UNEXPECTED_RESPONSE',
      message: `请求失败，HTTP ${response.status}`,
      path,
      runId: null,
      fieldErrors: {},
    }
    const body = (await response.json().catch(() => fallback)) as ApiErrorBody
    throw new ApiError(body)
  }
  return response.json() as Promise<T>
}

/** 提交一次新的 M0 Coding Run。 */
export function createRun(input: CreateRunInput) {
  return request<CreateRunResponse>('/api/v1/m0/runs', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  })
}

/** 查询 Run 的当前状态投影。 */
export function getRunStatus(runId: string) {
  return request<RunStatus>(`/api/v1/m0/runs/${runId}`)
}

/** 查询 Run 的最终业务结果。 */
export function getRunResult(runId: string) {
  return request<RunResult>(`/api/v1/m0/runs/${runId}/result`)
}

/** 查询 Run 已采集的代码变更。 */
export function getRunChanges(runId: string) {
  return request<RunChanges>(`/api/v1/m0/runs/${runId}/changes`)
}

/** 实时检查创建 Coding Run 所需的本机运行环境。 */
export function getRuntimeReadiness() {
  return request<RuntimeReadiness>('/api/v1/system/readiness')
}
