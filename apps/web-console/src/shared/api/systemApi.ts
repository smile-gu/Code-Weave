import { request } from './http'
import type { RuntimeReadiness } from './types'

/** 实时检查 Coding 所需的本机运行环境。 */
export function getRuntimeReadiness() {
  return request<RuntimeReadiness>('/api/v1/system/readiness')
}
