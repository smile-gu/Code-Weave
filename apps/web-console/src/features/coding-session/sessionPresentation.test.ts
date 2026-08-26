import { describe, expect, it } from 'vitest'
import { sessionStatusLabels } from './sessionPresentation'

describe('sessionStatusLabels', () => {
  it('maps every persisted session state to a user-facing label', () => {
    expect(sessionStatusLabels).toEqual({
      INITIALIZING: '正在初始化',
      ACTIVE: '可继续开发',
      LEGACY_READ_ONLY: '历史只读',
      CLOSED: '已关闭',
      MERGED: '已合并',
      FAILED: '初始化失败',
    })
  })
})
