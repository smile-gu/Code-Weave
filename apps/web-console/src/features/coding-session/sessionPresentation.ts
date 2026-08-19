import type { CodingSessionStatus } from '../../shared/api/types'

export const sessionStatusLabels: Record<CodingSessionStatus, string> = {
  INITIALIZING: '正在初始化',
  ACTIVE: '可继续开发',
  CLOSED: '已关闭',
  FAILED: '初始化失败',
  MERGED: '已合并',
}

/** 将后端时间转换为适合本地用户阅读的时间。 */
export function formatSessionTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}
