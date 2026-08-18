import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getCodingSession } from '../../shared/api/sessionApi'
import type { CodingSession } from '../../shared/api/types'
import { CodingSessionDetailPage } from './CodingSessionDetailPage'

vi.mock('../../shared/api/sessionApi', () => ({
  getCodingSession: vi.fn(),
}))

const activeSession: CodingSession = {
  sessionId: '8cf5fe15-2396-48fb-9ddf-c6eff21cd331',
  title: '用户模块持续开发',
  status: 'ACTIVE',
  sourceRepository: 'D:\\project\\demo',
  worktreePath: 'D:\\runtime\\worktrees\\8cf5fe15-2396-48fb-9ddf-c6eff21cd331',
  baseRef: 'HEAD',
  resolvedCommit: 'abc123',
  sourceRepositoryDirty: false,
  allowedPaths: ['src/**', 'pom.xml'],
  deniedPaths: ['.git/**', 'target/**'],
  createdAt: '2026-08-18T06:00:00Z',
  updatedAt: '2026-08-18T06:00:01Z',
  failureCode: null,
  failureMessage: null,
}

/** 使用真实路由参数和独立查询缓存渲染 Session 详情页。 */
function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/sessions/${activeSession.sessionId}`]}>
        <Routes>
          <Route path="/sessions/:sessionId" element={<CodingSessionDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('CodingSessionDetailPage', () => {
  beforeEach(() => {
    vi.mocked(getCodingSession).mockResolvedValue(activeSession)
  })

  it('shows persistent worktree and change policy', async () => {
    renderPage()

    expect(await screen.findByText('用户模块持续开发')).toBeInTheDocument()
    expect(screen.getByText(activeSession.worktreePath!)).toBeInTheDocument()
    expect(screen.getByText('src/**')).toBeInTheDocument()
    expect(screen.getByText('.git/**')).toBeInTheDocument()
    expect(getCodingSession).toHaveBeenCalledWith(activeSession.sessionId)
  })

  it('states that multi-turn coding belongs to the next stage', async () => {
    renderPage()

    expect(await screen.findByText('多轮 Coding Turn 尚未开放')).toBeInTheDocument()
    expect(screen.getByText(/本阶段只保证 Session 与 Worktree 跨重启持续存在/)).toBeInTheDocument()
  })
})
