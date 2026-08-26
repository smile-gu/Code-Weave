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
  includeExistingChanges: boolean
}

const initialForm: FormState = {
  title: '',
  repository: '',
  includeExistingChanges: false,
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
  const update = (field: 'title' | 'repository', value: string) => {
    setForm((current) => ({ ...current, [field]: value }))
  }

  /** 提交 Session 创建请求并在成功后进入详情页。 */
  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    mutation.mutate({
      title: form.title.trim() || null,
      repository: form.repository.trim(),
      baseRef: 'HEAD',
      allowedPaths: ['src/**', 'pom.xml'],
      deniedPaths: ['.git/**', 'target/**'],
      includeExistingChanges: form.includeExistingChanges,
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
            Session 直接绑定真实 Git 工作区。后续每轮 Coding、Review 和整改都在同一仓库与会话上下文中衔接。
          </p>
        </div>
        <div className="boundary-note session-boundary">
          <Info size={18} />
          <div>
            <strong>当前阶段边界</strong>
            <p>Coding Agent 会直接修改所选仓库；同一仓库同时只允许一个活跃 Session。</p>
          </div>
        </div>
      </section>

      <div className="session-grid">
        <section className="form-panel session-form-panel">
          <div className="section-heading">
            <span><MessagesSquare size={16} /> 创建持续会话</span>
            <small>一个 Session 对应一个真实 Git 工作区</small>
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
            <label>
              <span>审查范围</span>
              <input value="Spring Boot 3.x · Maven" disabled />
            </label>
            <label>
              <span>创建前的未提交修改</span>
              <select value={form.includeExistingChanges ? 'include' : 'exclude'}
                onChange={(event) => setForm((current) => ({
                  ...current,
                  includeExistingChanges: event.target.value === 'include',
                }))}>
                <option value="exclude">默认不纳入；存在修改时阻止创建</option>
                <option value="include">明确纳入当前 Session 的累计变更</option>
              </select>
            </label>
            {mutation.isError && (
              <div className="form-error" role="alert">
                <strong>{apiError?.code ?? 'SESSION_CREATE_FAILED'}</strong>
                <span>{mutation.error.message || '会话创建失败，请确认 Control Plane 已启动。'}</span>
              </div>
            )}
            <button className="primary-button" disabled={mutation.isPending} type="submit">
              {mutation.isPending ? '正在检查并锁定仓库…' : '创建 Coding Session'}
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
            <span>Coding Agent 直接修改真实仓库；仓库级写锁会阻止多个 Session 同时写入。</span>
          </div>
        </section>
      </div>
    </div>
  )
}
