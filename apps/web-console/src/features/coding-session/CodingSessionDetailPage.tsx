import { useQuery } from '@tanstack/react-query'
import { ArrowLeft, CheckCircle2, CircleAlert, Construction, FolderGit2, GitCommitHorizontal, ShieldCheck } from 'lucide-react'
import { Link, useParams } from 'react-router-dom'
import { getCodingSession } from '../../shared/api/sessionApi'
import { formatSessionTime, sessionStatusLabels } from './sessionPresentation'

/** 展示持久 Coding Session 的状态、工作区和路径边界。 */
export function CodingSessionDetailPage() {
  const { sessionId = '' } = useParams()
  const sessionQuery = useQuery({
    queryKey: ['coding-session', sessionId],
    queryFn: () => getCodingSession(sessionId),
    enabled: Boolean(sessionId),
    retry: false,
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

      <section className="session-detail-grid">
        <article>
          <header><FolderGit2 size={17} /><strong>工作区定位</strong></header>
          <dl className="session-properties">
            <div><dt>原始仓库</dt><dd>{session.sourceRepository}</dd></div>
            <div><dt>Session Worktree</dt><dd>{session.worktreePath ?? '未创建'}</dd></div>
            <div><dt>固定提交</dt><dd><GitCommitHorizontal size={14} /> {session.resolvedCommit ?? '未解析'}</dd></div>
          </dl>
        </article>
        <article>
          <header><ShieldCheck size={17} /><strong>修改边界</strong></header>
          <div className="policy-columns">
            <div><span>允许路径</span>{session.allowedPaths.map((path) => <code key={path}>{path}</code>)}</div>
            <div><span>禁止路径</span>{session.deniedPaths.map((path) => <code key={path}>{path}</code>)}</div>
          </div>
        </article>
      </section>

      <section className="turn-placeholder">
        {session.status === 'ACTIVE' ? <CheckCircle2 size={21} /> : <Construction size={21} />}
        <div>
          <strong>多轮 Coding Turn 尚未开放</strong>
          <p>本阶段只保证 Session 与 Worktree 跨重启持续存在。下一阶段会在这里加入任务输入、上下文续接、实时事件和历史 Turn。</p>
        </div>
      </section>
    </div>
  )
}
