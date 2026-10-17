import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, ArrowRight, CheckCircle2, CircleAlert, FolderGit2, GitCommitHorizontal, RefreshCw, Send, Square, MessageSquare, ShieldCheck, FileDiff } from 'lucide-react'
import { useCallback, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import {
  cancelCodingTurn,
  closeCodingSession,
  advanceReviewBaseline,
  getCodingSession,
  getCodingSessionChanges,
  getCodingTurns,
  getReviewBaselineReadiness,
  submitCodingTurn,
} from '../../shared/api/sessionApi'
import { ApiError } from '../../shared/api/http'
import { DiffPanel } from '../run-result/DiffPanel'
import { formatSessionTime, sessionStatusLabels } from './sessionPresentation'
import { TurnExecutionPanel } from './TurnExecutionPanel'
import { TurnMarkdown } from './TurnMarkdown'
import { ReviewDraftPanel } from './ReviewDraftPanel'
import { Skeleton, TechnicalDetails } from '../../shared/ui/surfaces'
import { PageEntrance } from '../../shared/ui/motion'
import { m } from 'motion/react'

const baselineBlockerLabels: Record<string, string> = {
  SESSION_NOT_ACTIVE: '会话当前不是可开发状态',
  DIRECT_WORKSPACE_REQUIRED: '历史隔离工作区不支持推进基线',
  TURN_IN_PROGRESS: 'Coding Agent 仍在执行',
  NO_VERIFIED_TURN: '当前模块尚无通过基础验证的 Coding 结果',
  NO_NEW_COMMIT: '尚未检测到新的 Git Commit',
  NO_CHANGES: '当前没有代码修改',
  UNCOMMITTED_CHANGES: '仓库仍有未提交修改',
  SOURCE_BRANCH_CHANGED: '仓库分支已经变化',
  HEAD_NOT_DESCENDANT: '当前 HEAD 不是活动基线的后继提交',
  CHANGES_NOT_VERIFIED: '当前提交内容与最近一次 Coding 验证结果不一致',
}

/** 展示多轮 Coding 对话、执行过程、累计 Diff 和活动基线门禁。 */
export function CodingSessionDetailPage() {
  const { sessionId = '' } = useParams()
  const sessionQuery = useQuery({
    queryKey: ['coding-session', sessionId],
    queryFn: () => getCodingSession(sessionId),
    enabled: Boolean(sessionId),
    retry: false,
  })
  const queryClient = useQueryClient()
  const turnsQuery = useQuery({
    queryKey: ['coding-turns', sessionId],
    queryFn: () => getCodingTurns(sessionId),
    enabled: Boolean(sessionId),
  })
  const [closeNotice, setCloseNotice] = useState<{ id: string; message: string; retry: boolean } | null>(null)
  const closeMutation = useMutation({
    mutationFn: (id: string) => closeCodingSession(id),
    onSuccess: async (closed, id) => {
      queryClient.setQueryData(['coding-session', id], closed)
      await queryClient.cancelQueries({ queryKey: ['coding-session-changes', id] })
      await queryClient.cancelQueries({ queryKey: ['review-baseline-readiness', id] })
      queryClient.removeQueries({ queryKey: ['coding-session-changes', id] })
      queryClient.removeQueries({ queryKey: ['review-baseline-readiness', id] })
      setCloseNotice({ id, message: '会话已关闭，仓库所有权已释放。', retry: false })
      void queryClient.invalidateQueries({ queryKey: ['coding-sessions'] })
      void queryClient.invalidateQueries({ queryKey: ['coding-session', id] })
    },
    onError: async (error, id) => {
      const uncertain = !(error instanceof ApiError) || error.status >= 500
      setCloseNotice({ id, message: error instanceof ApiError && error.code === 'SESSION_CLOSE_RELEASE_PENDING'
        ? '会话已关闭，仓库释放尚未确认。'
        : uncertain ? '关闭结果尚未确认，请重试确认仓库释放。' : error.message, retry: uncertain })
      // 查询只确认业务状态，不把 CLOSED 当作仓库释放证明。
      try {
        const current = await getCodingSession(id)
        queryClient.setQueryData(['coding-session', id], current)
        if (current.status === 'CLOSED') {
          await queryClient.cancelQueries({ queryKey: ['coding-session-changes', id] })
          await queryClient.cancelQueries({ queryKey: ['review-baseline-readiness', id] })
          queryClient.removeQueries({ queryKey: ['coding-session-changes', id] })
        }
      } catch { /* 保留待确认提示，允许同一关闭请求重试。 */ }
    },
  })
  const [prompt, setPrompt] = useState('')
  const [workspaceTab, setWorkspaceTab] = useState<'coding' | 'review' | 'changes' | 'baseline'>('coding')
  const submitMutation = useMutation({
    mutationFn: () => submitCodingTurn(sessionId, prompt),
    onSuccess: () => {
      setPrompt('')
      void queryClient.invalidateQueries({ queryKey: ['coding-turns', sessionId] })
    },
  })
  const cancelMutation = useMutation({
    mutationFn: (turnId: string) => cancelCodingTurn(sessionId, turnId),
    onSuccess: () => void queryClient.invalidateQueries({
      queryKey: ['coding-turns', sessionId],
    }),
  })
  const activeTurn = turnsQuery.data?.turns.find((turn) => turn.status === 'RUNNING' || turn.status === 'QUEUED')
  const refreshAfterTerminal = useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: ['coding-turns', sessionId] })
    void queryClient.invalidateQueries({ queryKey: ['coding-session', sessionId] })
    void queryClient.invalidateQueries({ queryKey: ['review-baseline-readiness', sessionId] })
    void queryClient.invalidateQueries({ queryKey: ['coding-session-changes', sessionId] })
  }, [queryClient, sessionId])
  const changesQuery = useQuery({
    queryKey: ['coding-session-changes', sessionId, sessionQuery.data?.activeReviewBaselineCommit],
    queryFn: () => getCodingSessionChanges(sessionId),
    enabled: sessionQuery.data?.status === 'ACTIVE' && !activeTurn && !closeMutation.isPending,
  })
  const baselineReadinessQuery = useQuery({
    queryKey: ['review-baseline-readiness', sessionId],
    queryFn: () => getReviewBaselineReadiness(sessionId),
    enabled: sessionQuery.data?.status === 'ACTIVE' && !activeTurn && !closeMutation.isPending,
  })
  const advanceBaselineMutation = useMutation({
    mutationFn: () => {
      const readiness = baselineReadinessQuery.data
      if (!readiness) throw new Error('正在读取 Git 提交检查结果')
      return advanceReviewBaseline(sessionId, {
        expectedActiveBaselineCommit: readiness.activeBaselineCommit,
        expectedHead: readiness.currentHead,
        workspaceFingerprint: readiness.workspaceFingerprint,
      })
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['coding-session', sessionId] })
      void queryClient.invalidateQueries({ queryKey: ['review-baseline-readiness', sessionId] })
    },
  })
  if (sessionQuery.isLoading) {
    return <div className="page-message">正在读取 Coding Session…</div>
  }
  if (sessionQuery.isError || !sessionQuery.data) {
    return (
      <div className="page-message error-page">
        <strong>无法读取这个 Coding Session</strong>
        <p>{sessionQuery.error?.message ?? '请确认会话标识和 Control Plane 状态。'}</p>
        <Link to="/sessions"><ArrowLeft size={16} /> 返回持续会话</Link>
      </div>
    )
  }

  const session = sessionQuery.data
  return (
    <PageEntrance className="session-detail-page">
      <div className="run-toolbar" data-entrance>
        <Link className="back-link" to="/sessions"><ArrowLeft size={16} /> 持续会话</Link>
        <span className={`session-status detail-status status-${session.status.toLowerCase()}`}>
          {sessionStatusLabels[session.status]}
        </span>
      </div>

      <section className="session-detail-hero" data-entrance>
        <div>
          <p className="eyebrow">CODING SESSION</p>
          <h1>{session.title}</h1>
          <p>{session.status === 'FAILED'
            ? session.failureMessage
            : session.status === 'CLOSED' ? '会话已关闭，当前工作区可能已发生后续修改；本会话不保存关闭时的 Diff 快照。'
            : '长期开发工作区已经就绪，可以承载后续多轮 Coding 与 Review。'}</p>
        </div>
        <dl>
          <div><dt>创建时间</dt><dd>{formatSessionTime(session.createdAt)}</dd></div>
          <div><dt>最后更新</dt><dd>{formatSessionTime(session.updatedAt)}</dd></div>
          <div><dt>原仓库状态</dt><dd>{session.sourceRepositoryDirty ? '创建时有未提交修改' : '创建时干净'}</dd></div>
        </dl>
      </section>

      {session.status === 'FAILED' && (
        <section className="session-failure">
          <CircleAlert size={19} />
          <div><strong>{session.failureCode}</strong><p>{session.failureMessage}</p></div>
        </section>
      )}

      {(session.status === 'ACTIVE' || session.status === 'CLOSED') && <section className="session-close">
        {session.status === 'ACTIVE' && <button type="button" className="secondary-button"
          disabled={Boolean(activeTurn) || turnsQuery.isPending || turnsQuery.isError || closeMutation.isPending}
          onClick={() => {
            if (window.confirm('关闭后无法在本会话继续开发。代码和未提交修改会保留，不会自动提交、回滚或删除。需要继续时可创建新会话。'))
              closeMutation.mutate(sessionId)
          }}>关闭会话</button>}
        {activeTurn && <p>请先停止任务并等待执行结束，再关闭会话。</p>}
        {closeNotice?.id === sessionId && <p role="status">{closeNotice.message}</p>}
        {(closeNotice?.id === sessionId && closeNotice.retry
          || session.status === 'CLOSED' && !(closeNotice?.id === sessionId && !closeNotice.retry)) &&
          <button type="button" disabled={closeMutation.isPending}
            onClick={() => closeMutation.mutate(sessionId)}>重试释放</button>}
        {session.closedAt && <p>关闭时间：{formatSessionTime(session.closedAt)}</p>}
      </section>}

      <section className="session-repository-strip" data-entrance>
        <FolderGit2 size={16} />
        <strong title={session.sourceRepository}>{session.sourceRepository.split(/[\\/]/).filter(Boolean).at(-1)}</strong>
        <span>本地仓库</span>
        <TechnicalDetails><dl><div><dt>仓库路径</dt><dd>{session.sourceRepository}</dd></div><div><dt>会话标识</dt><dd><code>{session.sessionId}</code></dd></div><div><dt>基准版本</dt><dd><code>{session.baseRef}</code></dd></div><div><dt>活动审查基线</dt><dd><code>{session.activeReviewBaselineCommit}</code></dd></div></dl></TechnicalDetails>
      </section>

      <nav className="workspace-view-tabs" aria-label="会话视图">
        {([
          ['coding', 'Coding 对话', MessageSquare], ['review', 'Review 审阅', ShieldCheck],
          ['changes', '代码变更', FileDiff], ['baseline', '模块交付', GitCommitHorizontal],
        ] as const).map(([value, label, Icon]) => <button key={value} type="button" aria-pressed={workspaceTab === value} aria-controls={`workspace-${value}`} onClick={() => setWorkspaceTab(value)}><Icon size={16} />{label}{value === 'coding' && activeTurn && <span className="tab-running-dot" />}{workspaceTab === value && <m.span layoutId={`workspace-tab-${sessionId}`} className="workspace-tab-indicator" transition={{ type: 'spring', stiffness: 480, damping: 38 }} />}</button>)}
      </nav>

      <div id="workspace-coding" hidden={workspaceTab !== 'coding'}>
      <section className="coding-conversation">
        <header className="conversation-heading">
          <span><CheckCircle2 size={18} /> 持续 Coding</span>
          <small>任务、执行进度与回复</small>
        </header>
        <div className="conversation-stream">
          {turnsQuery.isPending ? <div role="status" aria-label="正在读取对话"><Skeleton className="report-skeleton" /></div> : turnsQuery.isError ? <div className="workspace-empty-note" role="alert"><strong>对话暂时无法读取</strong><p>{turnsQuery.error.message}</p><button type="button" onClick={() => void turnsQuery.refetch()}>重新读取对话</button></div> : turnsQuery.data?.turns.length ? turnsQuery.data.turns.map((turn) => (
            <article className="conversation-turn" key={turn.turnId}>
              <div className="message-user">
                <div className="message-content">
                  <p>{turn.userPrompt}</p>
                </div>
                <time>{formatSessionTime(turn.createdAt)}</time>
              </div>

              <TurnExecutionPanel sessionId={sessionId} turn={turn} onTerminal={refreshAfterTerminal} />

              <div className="message-assistant">
                {turn.assistantMessage
                  ? <TurnMarkdown content={turn.assistantMessage} />
                  : <p className="assistant-placeholder">{turn.userMessage}</p>}
              </div>
            </article>
          )) : (
            <div className="conversation-empty">
              <span className="conversation-empty-mark"><MessageSquare size={25} /></span>
              <strong>开始第一次协作</strong>
              <p>在下方描述任务，Coding Agent 的执行过程和回复会连续展示在这里。</p>
            </div>
          )}
        </div>

        {session.status === 'ACTIVE' && (
          <form className="conversation-composer" onSubmit={(event) => {
            event.preventDefault()
            if (prompt.trim() && !activeTurn) submitMutation.mutate()
          }}>
            <textarea value={prompt} onChange={(event) => setPrompt(event.target.value)}
              placeholder="告诉 Coding Agent 下一步要完成什么" rows={4}
              disabled={Boolean(activeTurn)} />
            <footer>
              <small>{activeTurn ? `第 ${activeTurn.sequence} 轮正在执行，同一时间只运行一轮` : 'Enter 换行，点击按钮提交新一轮任务'}</small>
              {activeTurn ? (
                <button type="button" className="danger-button"
                  onClick={() => cancelMutation.mutate(activeTurn.turnId)}>
                  <Square size={15} /> 停止当前 Turn
                </button>
              ) : (
                <button type="submit" disabled={!prompt.trim() || submitMutation.isPending || closeMutation.isPending}>
                  <Send size={15} /> 发送
                </button>
              )}
            </footer>
            {submitMutation.isError && <p className="error-text">{submitMutation.error.message}</p>}
          </form>
        )}
      </section>
      </div>

      <div id="workspace-review" hidden={workspaceTab !== 'review'}>
      <ReviewDraftPanel key={sessionId} sessionId={sessionId} sessionActive={session.status === 'ACTIVE'} codingActive={Boolean(activeTurn)} />
      </div>

      <div id="workspace-changes" hidden={workspaceTab !== 'changes'}>
      {session.status === 'CLOSED' && <p className="workspace-empty-note">会话已关闭，不读取可能已经变化的工作区 Diff。</p>}
      {session.status === 'ACTIVE' && activeTurn && <p className="workspace-empty-note">Coding 正在执行，结束后再读取累计变更。</p>}
      {session.status === 'ACTIVE' && changesQuery.isLoading && <section className="session-changes"><p>正在读取累计代码变更…</p></section>}
      {session.status === 'ACTIVE' && changesQuery.data && <section className="session-changes">
          <header><strong>当前累计代码变更</strong><small>相对当前活动审查基线</small></header>
        <DiffPanel changes={changesQuery.data} />
      </section>}
      {session.status === 'ACTIVE' && changesQuery.isError && <p className="error-text" role="status">无法读取累计变更：{changesQuery.error.message}</p>}
      </div>

      <div id="workspace-baseline" hidden={workspaceTab !== 'baseline'}>
      {session.status === 'ACTIVE' && <section className="merge-panel">
        <header className="baseline-heading"><span className="surface-icon"><GitCommitHorizontal size={20} /></span><div>
          <h3>完成当前模块并推进基线</h3>
          <p>核对提交与验证状态，为下一模块建立起点。</p>
        </div></header>
        <div className="baseline-status" data-ready={baselineReadinessQuery.data?.ready === true}>
          <strong>{baselineReadinessQuery.isError ? '提交状态读取失败' : baselineReadinessQuery.isPending ? '正在检查提交状态' : baselineReadinessQuery.data?.ready ? '提交已就绪' : '尚未满足归档条件'}</strong>
          <p>{baselineReadinessQuery.isError ? '暂时无法确认提交状态，请重新检查。' : baselineReadinessQuery.data?.ready
            ? `已检测到 ${baselineReadinessQuery.data.branch} 上的模块提交，其中 ${baselineReadinessQuery.data.changedFiles} 个文件与最近一次 Coding 验证结果一致。`
            : `当前模块暂不能归档：${baselineReadinessQuery.data?.blockers.map((code) => baselineBlockerLabels[code] ?? code).join('、') ?? '正在检查'}。`}</p>
        </div>
          <p className="baseline-guidance">请在 Review、问题修复和最终自测全部完成后，只为当前模块创建一次正式 Git Commit。平台不会自动提交或推送代码。</p>
        <div className="baseline-actions" role="group" aria-label="模块交付操作">
          <button type="button" className="secondary-button"
            disabled={baselineReadinessQuery.isFetching}
            onClick={() => void baselineReadinessQuery.refetch()}>
            <RefreshCw size={15} /> 重新检查提交状态
          </button>
          <button type="button" className="primary-button"
            disabled={!baselineReadinessQuery.data?.ready || advanceBaselineMutation.isPending}
            onClick={() => {
              if (window.confirm('请确认当前模块已经完成 Review、问题修复和最终自测。是否将这次正式提交设为新活动基线？')) {
                advanceBaselineMutation.mutate()
              }
            }}>
            确认模块提交并开始下一模块 <ArrowRight size={15} />
          </button>
        </div>
          {advanceBaselineMutation.isError && <p className="error-text" role="alert">{advanceBaselineMutation.error.message}</p>}
        <details className="baseline-history-note"><summary>归档与历史说明</summary><p>原始 Result 保持只读，人工修订独立存储；Review 与最终自测是否完成仍由你确认，平台校验提交来源和代码指纹。</p></details>
      </section>}
      {session.status === 'CLOSED' && <p className="workspace-empty-note">已关闭的会话只保留历史，不推进活动基线。</p>}
      </div>
    </PageEntrance>
  )
}
