import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  advanceReviewBaseline,
  getCodingSession,
  getCodingTurnChanges,
  getCodingTurns,
  getReviewBaselineReadiness,
} from '../../shared/api/sessionApi'
import type { CodingSession } from '../../shared/api/types'
import { CodingSessionDetailPage } from './CodingSessionDetailPage'

vi.mock('../../shared/api/sessionApi', () => ({
  getCodingSession: vi.fn(),
  getCodingTurns: vi.fn(),
  getReviewBaselineReadiness: vi.fn(),
  getCodingTurnChanges: vi.fn(),
  submitCodingTurn: vi.fn(),
  cancelCodingTurn: vi.fn(),
  advanceReviewBaseline: vi.fn(),
}))

const activeSession: CodingSession = {
  sessionId: '8cf5fe15-2396-48fb-9ddf-c6eff21cd331',
  title: '用户模块持续开发',
  status: 'ACTIVE',
  sourceRepository: 'D:\\project\\demo',
  worktreePath: 'D:\\project\\demo',
  baseRef: 'main',
  resolvedCommit: 'abc123',
  activeReviewBaselineCommit: 'abc123',
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
    vi.mocked(getCodingTurns).mockResolvedValue({ turns: [] })
    vi.mocked(getCodingTurnChanges).mockResolvedValue({
      runId: 'turn-1', baseCommit: 'abc123', workspaceRoot: activeSession.sourceRepository,
      totalFiles: 1, unifiedDiffBytes: 0, files: [], unifiedDiff: '',
    })
    vi.mocked(getReviewBaselineReadiness).mockResolvedValue({
      sessionId: activeSession.sessionId,
      ready: false,
      activeBaselineCommit: 'abc123',
      currentHead: 'abc123',
      branch: 'main',
      changedFiles: 0,
      workspaceFingerprint: 'sha256:empty',
      blockers: ['NO_VERIFIED_TURN', 'NO_NEW_COMMIT', 'NO_CHANGES'],
    })
  })

  it('keeps only the source repository visible as low-weight workspace context', async () => {
    renderPage()

    expect(await screen.findByText('用户模块持续开发')).toBeInTheDocument()
    expect(screen.getAllByText(activeSession.sourceRepository)).toHaveLength(1)
    expect(screen.queryByText('.git/**')).not.toBeInTheDocument()
    expect(getCodingSession).toHaveBeenCalledWith(activeSession.sessionId)
  })

  it('shows multi-turn coding and the explicit review-baseline boundary', async () => {
    renderPage()

    expect(await screen.findByText('持续 Coding')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('告诉 Coding Agent 下一步要完成什么')).toBeInTheDocument()
    expect(await screen.findByText(/当前模块尚无通过基础验证的 Coding 结果/)).toBeInTheDocument()
    expect(screen.getByText(/Review、问题修复和最终自测全部完成后/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '确认模块提交并开始下一模块' })).toBeDisabled()
    expect(screen.queryByText('确认合并到源码')).not.toBeInTheDocument()
  })

  it('enables baseline advance only after a verified clean Git commit', async () => {
    vi.mocked(getReviewBaselineReadiness).mockResolvedValue({
      sessionId: activeSession.sessionId,
      ready: true,
      activeBaselineCommit: 'abc123',
      currentHead: 'def456',
      branch: 'main',
      changedFiles: 2,
      workspaceFingerprint: 'sha256:verified',
      blockers: [],
    })
    vi.mocked(advanceReviewBaseline).mockResolvedValue({
      ...activeSession,
      activeReviewBaselineCommit: 'def456',
    })

    renderPage()

    expect(await screen.findByText(/已检测到 main 上的模块提交，其中 2 个文件/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '确认模块提交并开始下一模块' })).toBeEnabled()
  })

  it('renders assistant Markdown and the aggregated five-stage workflow', async () => {
    vi.mocked(getCodingTurns).mockResolvedValue({ turns: [{
      turnId: 'turn-1', sessionId: activeSession.sessionId, sequence: 1,
      userPrompt: '新增用户接口', status: 'COMPLETED', outcome: 'CHANGES_VERIFIED',
      assistantMessage: '## 已完成\n\n- 新增 `UserController`', changedFiles: 1,
      workspaceFingerprint: 'sha256:verified', failureCode: null,
      userMessage: 'Maven 验证通过。', createdAt: '2026-08-18T06:01:00Z',
      startedAt: '2026-08-18T06:01:01Z', endedAt: '2026-08-18T06:02:00Z',
    }] })

    renderPage()

    expect(await screen.findByRole('heading', { name: '已完成' })).toBeInTheDocument()
    expect(screen.getByText('UserController')).toBeInTheDocument()
    expect(screen.getByRole('list', { name: '第 1 轮 Coding 阶段进度' })).toBeInTheDocument()
    expect(screen.getByText('Maven 编译与测试通过。')).toBeInTheDocument()
  })
})
