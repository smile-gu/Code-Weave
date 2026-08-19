import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, CheckCircle2, CircleAlert, FolderGit2, GitCommitHorizontal, Send, Square } from 'lucide-react'
import { useCallback, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import {
  cancelCodingTurn,
  getCodingSession,
  getCodingSessionIntegration,
  getCodingTurnChanges,
  getCodingTurns,
  getMergeReadiness,
  mergeCodingSession,
  submitCodingTurn,
} from '../../shared/api/sessionApi'
import { DiffPanel } from '../run-result/DiffPanel'
import { formatSessionTime, sessionStatusLabels } from './sessionPresentation'
import { TurnExecutionPanel } from './TurnExecutionPanel'
import { TurnMarkdown } from './TurnMarkdown'

const mergeBlockerLabels: Record<string, string> = {
  SESSION_NOT_ACTIVE: '会话当前不是可开发状态',
  INTEGRATION_IN_PROGRESS: '源码合并正在进行',
  INTEGRATION_NEEDS_ATTENTION: '上次合并需要人工检查',
  SESSION_ALREADY_MERGED: '会话已经写回源码',
  TURN_IN_PROGRESS: 'Coding Agent 仍在执行',
  NO_VERIFIED_TURN: '最新一轮代码尚未通过 Maven 验证',
  NO_CHANGES: '当前没有代码修改',
  WORKSPACE_CHANGED_AFTER_VERIFICATION: '验证后的代码又发生了变化',
  SOURCE_REPOSITORY_DIVERGED: '原仓库已经出现新提交',
  SOURCE_BRANCH_DETACHED: '原仓库当前不在分支上',
  SOURCE_REPOSITORY_DIRTY: '原仓库存在未提交修改',
  GIT_IDENTITY_MISSING: 'Git user.name 或 user.email 未配置',
}

const integrationStatusLabels = {
  MERGING: '正在写回源码',
  MERGED: '已写回源码',
  INTERRUPTED: '写回被中断，可重试',
  NEEDS_ATTENTION: '写回需要人工检查',
} as const

/** 展示持久 Coding Session 的状态、工作区和路径边界。 */
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
    void queryClient.invalidateQueries({ queryKey: ['merge-readiness', sessionId] })
  }, [queryClient, sessionId])
  const latestChangedTurn = [...(turnsQuery.data?.turns ?? [])]
    .reverse()
    .find((turn) => turn.changedFiles > 0 && turn.status !== 'QUEUED' && turn.status !== 'RUNNING')
  const changesQuery = useQuery({
    queryKey: ['coding-turn-changes', sessionId, latestChangedTurn?.turnId],
    queryFn: () => getCodingTurnChanges(sessionId, latestChangedTurn!.turnId),
    enabled: Boolean(latestChangedTurn),
  })
  const mergeReadinessQuery = useQuery({
    queryKey: ['merge-readiness', sessionId],
    queryFn: () => getMergeReadiness(sessionId),
    enabled: sessionQuery.data?.status === 'ACTIVE' && !activeTurn,
  })
  const integrationQuery = useQuery({
    queryKey: ['coding-session-integration', sessionId],
    queryFn: () => getCodingSessionIntegration(sessionId),
    enabled: Boolean(sessionId),
  })
  const mergeMutation = useMutation({
    mutationFn: () => {
      const readiness = mergeReadinessQuery.data
      if (!readiness) throw new Error('正在读取合并检查结果')
      return mergeCodingSession(sessionId, { commitMessage: `feat: 合并 ${sessionQuery.data?.title ?? 'Coding Session'} 的已验证修改`, expectedSourceHead: readiness.sourceHead, expectedTargetBranch: readiness.targetBranch, workspaceFingerprint: readiness.workspaceFingerprint })
    },
    onSuccess: () => { void queryClient.invalidateQueries({ queryKey: ['coding-session', sessionId] }); void queryClient.invalidateQueries({ queryKey: ['merge-readiness', sessionId] }) },
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
          <strong>写回原始仓库</strong>
          <p>{mergeReadinessQuery.data?.ready
            ? `已验证 ${mergeReadinessQuery.data.changedFiles} 个文件，可以 Fast-forward 到 ${mergeReadinessQuery.data.targetBranch}。`
            : `当前不可合并：${mergeReadinessQuery.data?.blockers.map((code) => mergeBlockerLabels[code] ?? code).join('、') ?? '正在检查'}。`}</p>
          <button type="button"
            disabled={!mergeReadinessQuery.data?.ready || mergeMutation.isPending}
            onClick={() => {
              if (window.confirm('系统将创建正式 Git Commit，并写回原始仓库。确认继续？')) {
                mergeMutation.mutate()
              }
            }}>
            确认合并到源码
          </button>
          {mergeMutation.isError && <p className="error-text">{mergeMutation.error.message}</p>}
        </div>
      </section>}

      {integrationQuery.data && <section className="merge-panel integration-state">
        <GitCommitHorizontal size={21} />
        <div>
          <strong>源码写回记录：{integrationStatusLabels[integrationQuery.data.status]}</strong>
          <p>{integrationQuery.data.failureMessage
            ?? (integrationQuery.data.status === 'MERGED'
              ? `原仓库已前进到 ${integrationQuery.data.mergedHead}`
              : '系统已保存本次写回的关键事实，可据此继续处理。')}</p>
        </div>
      </section>}
    </div>
  )
}
