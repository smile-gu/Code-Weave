import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, CheckCircle2, CircleAlert, FolderGit2, GitCommitHorizontal, RefreshCw, Send, Square } from 'lucide-react'
import { useCallback, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import {
  cancelCodingTurn,
  advanceReviewBaseline,
  getCodingSession,
  getCodingTurnChanges,
  getCodingTurns,
  getReviewBaselineReadiness,
  submitCodingTurn,
} from '../../shared/api/sessionApi'
import { DiffPanel } from '../run-result/DiffPanel'
import { formatSessionTime, sessionStatusLabels } from './sessionPresentation'
import { TurnExecutionPanel } from './TurnExecutionPanel'
import { TurnMarkdown } from './TurnMarkdown'

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
  const [prompt, setPrompt] = useState('')
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
  }, [queryClient, sessionId])
  const latestChangedTurn = [...(turnsQuery.data?.turns ?? [])]
    .reverse()
    .find((turn) => turn.changedFiles > 0 && turn.status !== 'QUEUED' && turn.status !== 'RUNNING')
  const changesQuery = useQuery({
    queryKey: ['coding-turn-changes', sessionId, latestChangedTurn?.turnId],
    queryFn: () => getCodingTurnChanges(sessionId, latestChangedTurn!.turnId),
    enabled: Boolean(latestChangedTurn),
  })
  const baselineReadinessQuery = useQuery({
    queryKey: ['review-baseline-readiness', sessionId],
    queryFn: () => getReviewBaselineReadiness(sessionId),
    enabled: sessionQuery.data?.status === 'ACTIVE' && !activeTurn,
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
    <div className="session-detail-page">
      <div className="run-toolbar">
        <Link className="back-link" to="/sessions"><ArrowLeft size={16} /> 持续会话</Link>
        <div className="run-identity">
          <span>SESSION</span>
          <code>{session.sessionId}</code>
        </div>
        <span className={`session-status detail-status status-${session.status.toLowerCase()}`}>
          {sessionStatusLabels[session.status]}
        </span>
      </div>

      <section className="session-detail-hero">
        <div>
          <p className="eyebrow">CODING SESSION</p>
          <h1>{session.title}</h1>
          <p>{session.status === 'FAILED'
            ? session.failureMessage
            : '长期开发工作区已经就绪，可以承载后续多轮 Coding 与 Review。'}</p>
        </div>
        <dl>
          <div><dt>创建时间</dt><dd>{formatSessionTime(session.createdAt)}</dd></div>
          <div><dt>最后更新</dt><dd>{formatSessionTime(session.updatedAt)}</dd></div>
          <div><dt>基准版本</dt><dd>{session.baseRef}</dd></div>
          <div><dt>原仓库状态</dt><dd>{session.sourceRepositoryDirty ? '创建时有未提交修改' : '创建时干净'}</dd></div>
        </dl>
      </section>

      {session.status === 'FAILED' && (
        <section className="session-failure">
          <CircleAlert size={19} />
          <div><strong>{session.failureCode}</strong><p>{session.failureMessage}</p></div>
        </section>
      )}

      <section className="session-repository-strip">
        <FolderGit2 size={16} />
        <span>原始仓库</span>
        <code title={session.sourceRepository}>{session.sourceRepository}</code>
      </section>

      <section className="coding-conversation">
        <header className="conversation-heading">
          <span><CheckCircle2 size={18} /> 持续 Coding</span>
          <small>复用当前代码工作区与 Claude 原生上下文</small>
        </header>
        <div className="conversation-stream">
          {turnsQuery.data?.turns.length ? turnsQuery.data.turns.map((turn) => (
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
                <button type="submit" disabled={!prompt.trim() || submitMutation.isPending}>
                  <Send size={15} /> 发送
                </button>
              )}
            </footer>
            {submitMutation.isError && <p className="error-text">{submitMutation.error.message}</p>}
          </form>
        )}
      </section>

      {changesQuery.isLoading && <section className="session-changes"><p>正在读取累计代码变更…</p></section>}
      {changesQuery.data && <section className="session-changes">
        <header><strong>当前累计代码变更</strong><small>相对 Session 创建基线</small></header>
        <DiffPanel changes={changesQuery.data} />
      </section>}

      {session.status === 'ACTIVE' && <section className="merge-panel">
        <GitCommitHorizontal size={21} />
        <div>
          <strong>完成当前模块并推进基线</strong>
          <p>{baselineReadinessQuery.data?.ready
            ? `已检测到 ${baselineReadinessQuery.data.branch} 上的模块提交，其中 ${baselineReadinessQuery.data.changedFiles} 个文件与最近一次 Coding 验证结果一致。`
            : `当前模块暂不能归档：${baselineReadinessQuery.data?.blockers.map((code) => baselineBlockerLabels[code] ?? code).join('、') ?? '正在检查'}。`}</p>
          <p>请在 Review、问题修复和最终自测全部完成后，只为当前模块创建一次正式 Git Commit。平台不会自动提交或推送代码。</p>
          <p>Review Harness 接入前，Review 与最终自测是否完成由你确认；平台当前负责校验提交来源和代码指纹。</p>
          <button type="button" className="secondary-button"
            disabled={baselineReadinessQuery.isFetching}
            onClick={() => void baselineReadinessQuery.refetch()}>
            <RefreshCw size={15} /> 重新检查提交状态
          </button>
          <button type="button"
            disabled={!baselineReadinessQuery.data?.ready || advanceBaselineMutation.isPending}
            onClick={() => {
              if (window.confirm('请确认当前模块已经完成 Review、问题修复和最终自测。是否将这次正式提交设为新活动基线？')) {
                advanceBaselineMutation.mutate()
              }
            }}>
            确认模块提交并开始下一模块
          </button>
          {advanceBaselineMutation.isError && <p className="error-text">{advanceBaselineMutation.error.message}</p>}
        </div>
      </section>}
    </div>
  )
}
