import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { ActivityItem } from './activityProjection'
import { ActivityTimeline } from './ActivityTimeline'

const activity = (overrides: Partial<ActivityItem>): ActivityItem => ({
  id: 'activity-1',
  parentId: null,
  category: 'agent',
  title: 'Coding Agent 执行完成',
  summary: 'Agent 正常退出。',
  status: 'success',
  timestamp: '2026-08-18T05:43:50Z',
  sourceSequences: [1],
  details: [],
  ...overrides,
})

describe('ActivityTimeline', () => {
  it('将 Coding Agent 产生的活动嵌套在 Agent 主活动下', () => {
    render(<ActivityTimeline activities={[
      activity({ id: 'stage:coding:1' }),
      activity({
        id: 'agent:tool:1',
        parentId: 'stage:coding:1',
        title: '已读取 pom.xml',
        summary: '已读取项目配置。',
      }),
    ]} />)

    const childList = screen.getByRole('list', { name: 'Coding Agent 执行完成的执行明细' })
    expect(within(childList).getByText('已读取 pom.xml')).toBeInTheDocument()
    expect(within(childList).queryByText('Coding Agent 执行完成')).not.toBeInTheDocument()
  })

  it('父活动缺失时仍将活动作为根节点展示', () => {
    render(<ActivityTimeline activities={[
      activity({ id: 'agent:tool:1', parentId: 'missing-parent', title: '代码搜索完成' }),
    ]} />)

    expect(screen.getByText('代码搜索完成')).toBeInTheDocument()
  })
})
