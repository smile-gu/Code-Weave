import type { ApiErrorBody } from './types'

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
export async function request<T>(path: string, init?: RequestInit): Promise<T> {
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
