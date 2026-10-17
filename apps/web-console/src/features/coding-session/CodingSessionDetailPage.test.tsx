import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  advanceReviewBaseline,
  submitCodingTurn,
  closeCodingSession,
  getCodingSession,
  getCodingSessionChanges,
  getCodingTurns,
  getReviewBaselineReadiness,
} from '../../shared/api/sessionApi'
import { ApiError } from '../../shared/api/http'
import type { CodingSession } from '../../shared/api/types'
import { CodingSessionDetailPage } from './CodingSessionDetailPage'

vi.mock('../../shared/api/sessionApi', () => ({
  closeCodingSession: vi.fn(),
  getCodingTurnEvents: vi.fn().mockResolvedValue([]),
  getCodingSession: vi.fn(),
  getCodingTurns: vi.fn(),
  getReviewBaselineReadiness: vi.fn(),
  getCodingSessionChanges: vi.fn(),
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
    vi.clearAllMocks()
    vi.mocked(getCodingSession).mockResolvedValue(activeSession)
    vi.mocked(getCodingTurns).mockResolvedValue({ turns: [] })
    vi.mocked(getCodingSessionChanges).mockResolvedValue({
      baseCommit: 'abc123', workspaceRoot: activeSession.sourceRepository,
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

  it('closed history never reads mutable Diff or offers coding input', async () => {
    vi.mocked(getCodingSession).mockResolvedValue({ ...activeSession, status: 'CLOSED' })
    renderPage()
    expect(await screen.findByText(/本会话不保存关闭时的 Diff 快照/)).toBeInTheDocument()
    expect(getCodingSessionChanges).not.toHaveBeenCalled()
    expect(getReviewBaselineReadiness).not.toHaveBeenCalled()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  })

  it('closes explicitly and confirms release without retaining cached Diff', async () => {
    const closed = { ...activeSession, status: 'CLOSED' as const }
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(closeCodingSession).mockImplementation(async () => {
      vi.mocked(getCodingSession).mockResolvedValue(closed)
      return closed
    })
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: '关闭会话' }))
    expect(await screen.findByText('会话已关闭，仓库所有权已释放。')).toBeInTheDocument()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(screen.queryByText('当前累计代码变更')).not.toBeInTheDocument()
    expect(closeCodingSession).toHaveBeenCalledWith(activeSession.sessionId)
  })

  it('timeout confirms only business status and requires idempotent release retry', async () => {
    const closed = { ...activeSession, status: 'CLOSED' as const }
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(closeCodingSession).mockImplementationOnce(async () => {
      vi.mocked(getCodingSession).mockResolvedValue(closed)
      throw new TypeError('network')
    }).mockResolvedValue(closed)
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: '关闭会话' }))
    expect(await screen.findByText('关闭结果尚未确认，请重试确认仓库释放。')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('textbox')).not.toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '重试释放' }))
    expect(await screen.findByText('会话已关闭，仓库所有权已释放。')).toBeInTheDocument()
    expect(closeCodingSession).toHaveBeenCalledTimes(2)
  })

  it('release pending keeps closed and offers retry without claiming availability', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(closeCodingSession).mockImplementationOnce(async () => {
      vi.mocked(getCodingSession).mockResolvedValue({ ...activeSession, status: 'CLOSED' })
      throw new ApiError({ code: 'SESSION_CLOSE_RELEASE_PENDING', status: 503,
        message: 'pending', timestamp: '', path: '', runId: null, fieldErrors: {} })
    })
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: '关闭会话' }))
    expect(await screen.findByText('会话已关闭，仓库释放尚未确认。')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重试释放' })).toBeInTheDocument()
    expect(screen.queryByText('会话已关闭，仓库所有权已释放。')).not.toBeInTheDocument()
  })

  it('backend conflict never pretends closing succeeded', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(closeCodingSession).mockRejectedValueOnce(new ApiError({
      code: 'TURN_IN_PROGRESS', status: 409, message: '任务仍在清理',
      timestamp: '', path: '', runId: null, fieldErrors: {} }))
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: '关闭会话' }))
    expect(await screen.findByText('任务仍在清理')).toBeInTheDocument()
    expect(screen.getByRole('textbox')).toBeInTheDocument()
    expect(screen.queryByText('会话已关闭，仓库所有权已释放。')).not.toBeInTheDocument()
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
    fireEvent.click(screen.getByRole('button', { name: '模块交付' }))
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
    fireEvent.click(screen.getByRole('button', { name: '模块交付' }))
    expect(screen.getByRole('button', { name: '确认模块提交并开始下一模块' })).toBeEnabled()
  })

  it('preserves the unsent Coding draft across views without dispatching a Turn', async () => {
    renderPage()
    await screen.findByText('用户模块持续开发')
    const draft = screen.getByPlaceholderText('告诉 Coding Agent 下一步要完成什么')
    fireEvent.change(draft, { target: { value: '尚未发送的实现计划' } })
    fireEvent.click(screen.getByRole('button', { name: 'Review 审阅' }))
    expect(screen.getByRole('button', { name: 'Review 审阅' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '代码变更' }))
    expect(screen.getByText('当前累计代码变更')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: 'Coding 对话' }))
    expect(screen.getByPlaceholderText('告诉 Coding Agent 下一步要完成什么')).toHaveValue('尚未发送的实现计划')
    expect(submitCodingTurn).not.toHaveBeenCalled()
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
    expect(await screen.findByText('历史记录未提供完整阶段信息。')).toBeInTheDocument()
  })
})
