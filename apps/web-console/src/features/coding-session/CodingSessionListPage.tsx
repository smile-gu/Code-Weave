import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowRight, Clock3, FolderGit2, Info, MessagesSquare, ShieldCheck } from 'lucide-react'
import { Link, useNavigate } from 'react-router-dom'
import { ApiError } from '../../shared/api/runApi'
import { createCodingSession, getRecentCodingSessions } from '../../shared/api/sessionApi'
import type { CodingSessionStatus } from '../../shared/api/types'
import { formatSessionTime, sessionStatusLabels } from './sessionPresentation'

type FormState = {
  title: string
  repository: string
  baseRef: string
  allowedPaths: string
  deniedPaths: string
}

const initialForm: FormState = {
  title: '',
  repository: '',
  baseRef: 'HEAD',
  allowedPaths: 'src/**\npom.xml',
  deniedPaths: '.git/**\ntarget/**',
}

/** 将多行路径输入整理为后端接受的规则数组。 */
function lines(value: string) {
  return value.split(/\r?\n/).map((item) => item.trim()).filter(Boolean)
}

/** 展示 Coding Session 创建入口和最近持久历史。 */
export function CodingSessionListPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [form, setForm] = useState(initialForm)
  const sessionsQuery = useQuery({
    queryKey: ['coding-sessions'],
    queryFn: () => getRecentCodingSessions(20),
    retry: false,
  })
  const mutation = useMutation({
    mutationFn: createCodingSession,
    onSuccess: async (session) => {
      await queryClient.invalidateQueries({ queryKey: ['coding-sessions'] })
      navigate(`/sessions/${session.sessionId}`)
    },
  })

  /** 更新 Session 创建表单中的单个字段。 */
  const update = (field: keyof FormState, value: string) => {
    setForm((current) => ({ ...current, [field]: value }))
  }

  /** 提交 Session 创建请求并在成功后进入详情页。 */
  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    mutation.mutate({
      title: form.title.trim() || null,
      repository: form.repository.trim(),
      baseRef: form.baseRef.trim(),
      allowedPaths: lines(form.allowedPaths),
      deniedPaths: lines(form.deniedPaths),
    })
  }

  const apiError = mutation.error instanceof ApiError ? mutation.error : null

  return (
    <div className="session-home">
      <section className="session-hero">
        <div>
          <p className="eyebrow">CONTINUOUS CODING</p>
          <h1>为持续开发准备<br />一个稳定工作区</h1>
          <p>
            Session 会长期保留独立 Git Worktree。后续每轮 Coding、Review 和整改都将在同一会话上下文中衔接。
          </p>
        </div>
        <div className="boundary-note session-boundary">
          <Info size={18} />
          <div>
            <strong>当前阶段边界</strong>
            <p>持续 Session 承载多轮 Coding、实时五阶段进度、代码变更检查和用户确认合并。</p>
          </div>
        </div>
      </section>

      <div className="session-grid">
        <section className="form-panel session-form-panel">
          <div className="section-heading">
            <span><MessagesSquare size={16} /> 创建持续会话</span>
            <small>一个 Session 对应一个长期 Worktree</small>
          </div>
          <form onSubmit={submit}>
            <label>
              <span>会话名称 <small>可选</small></span>
              <input
                autoFocus
                value={form.title}
                onChange={(event) => update('title', event.target.value)}
                placeholder="例如：用户模块持续开发"
              />
              {apiError?.fieldErrors.title && <em>{apiError.fieldErrors.title}</em>}
            </label>
            <label>
              <span>本地 Git 仓库 <b>*</b></span>
              <input
                value={form.repository}
                onChange={(event) => update('repository', event.target.value)}
                placeholder="D:\personal_project\your-spring-project"
                required
              />
              {apiError?.fieldErrors.repository && <em>{apiError.fieldErrors.repository}</em>}
            </label>
            <div className="field-row">
              <label>
                <span>基准版本</span>
                <input value={form.baseRef} onChange={(event) => update('baseRef', event.target.value)} />
              </label>
              <label>
                <span>审查范围</span>
                <input value="Spring Boot 3.x · Maven" disabled />
              </label>
            </div>
            <div className="field-row">
              <label>
                <span>允许修改路径 <small>每行一条</small></span>
                <textarea value={form.allowedPaths} onChange={(event) => update('allowedPaths', event.target.value)} />
                {apiError?.fieldErrors.allowedPaths && <em>{apiError.fieldErrors.allowedPaths}</em>}
              </label>
              <label>
                <span>禁止修改路径 <small>每行一条</small></span>
                <textarea value={form.deniedPaths} onChange={(event) => update('deniedPaths', event.target.value)} />
                {apiError?.fieldErrors.deniedPaths && <em>{apiError.fieldErrors.deniedPaths}</em>}
              </label>
            </div>
            {mutation.isError && (
              <div className="form-error" role="alert">
                <strong>{apiError?.code ?? 'SESSION_CREATE_FAILED'}</strong>
                <span>{mutation.error.message || '会话创建失败，请确认 Control Plane 已启动。'}</span>
              </div>
            )}
            <button className="primary-button" disabled={mutation.isPending} type="submit">
              {mutation.isPending ? '正在创建 Worktree…' : '创建 Coding Session'}
              {!mutation.isPending && <ArrowRight size={17} />}
            </button>
          </form>
        </section>

        <section className="session-history">
          <div className="section-heading">
            <span><Clock3 size={16} /> 最近会话</span>
            <small>最近更新的 20 条</small>
          </div>
          {sessionsQuery.isLoading && <div className="session-history-message">正在读取持久历史…</div>}
          {sessionsQuery.isError && (
            <div className="session-history-message error-text">
              无法读取历史：{sessionsQuery.error.message}
            </div>
          )}
          {sessionsQuery.data?.sessions.length === 0 && (
            <div className="session-history-message">
              <FolderGit2 size={24} />
              尚未创建 Coding Session
            </div>
          )}
          <div className="session-cards">
            {sessionsQuery.data?.sessions.map((session) => (
              <Link className="session-card" key={session.sessionId} to={`/sessions/${session.sessionId}`}>
                <div className="session-card-heading">
                  <strong>{session.title}</strong>
                  <span className={`session-status status-${session.status.toLowerCase() as Lowercase<CodingSessionStatus>}`}>
                    {sessionStatusLabels[session.status]}
                  </span>
                </div>
                <p title={session.sourceRepository}>{session.sourceRepository}</p>
                <div>
                  <span>{session.baseRef}</span>
                  <time>{formatSessionTime(session.updatedAt)}</time>
                </div>
              </Link>
            ))}
          </div>
          <div className="session-assurance">
            <ShieldCheck size={17} />
            <span>原始仓库默认不被直接修改，所有后续工作都限定在 Session Worktree。</span>
          </div>
        </section>
      </div>
    </div>
  )
}
