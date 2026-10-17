import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowRight, Clock3, FolderGit2, Info, MessagesSquare, ShieldCheck, FolderOpen } from 'lucide-react'
import { Link, useNavigate } from 'react-router-dom'
import { ApiError } from '../../shared/api/http'
import { createCodingSession, getRecentCodingSessions } from '../../shared/api/sessionApi'
import type { CodingSessionStatus } from '../../shared/api/types'
import { formatSessionTime, sessionStatusLabels } from './sessionPresentation'
import { Badge, Card, CardContent, CardHeader, Skeleton } from '../../shared/ui/surfaces'
import { PageEntrance } from '../../shared/ui/motion'

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
    <PageEntrance className="session-home">
      <section className="session-hero" data-entrance>
        <div>
          <h1><span>让每一次代码变更，</span><span>清晰可控。</span></h1>
          <p>连接本地仓库，开始开发、审查与修复。</p>
        </div>
        <div className="boundary-note session-boundary">
          <Info size={18} />
          <div>
            <strong>你的代码，始终由你掌控</strong>
            <p>Coding Agent 会直接修改所选仓库；同一仓库同时只允许一个活跃 Session。</p>
          </div>
        </div>
      </section>

      <div className="workspace-stats" aria-label="最近会话概览" data-entrance>
        {[{ label: '最近会话', value: sessionsQuery.data?.sessions.length, icon: MessagesSquare },
          { label: '进行中', value: sessionsQuery.data?.sessions.filter(s => s.status === 'ACTIVE').length, icon: FolderOpen },
          { label: '已关闭', value: sessionsQuery.data?.sessions.filter(s => s.status === 'CLOSED').length, icon: Clock3 }].map(({ label, value, icon: Icon }) => <Card key={label}><CardHeader><span>{label}</span><Icon size={17} /></CardHeader><CardContent>{sessionsQuery.isPending ? <Skeleton className="stat-skeleton" /> : <strong>{sessionsQuery.isError ? '—' : value}</strong>}<small>最近 20 条内</small></CardContent></Card>)}
      </div>

      <div className="session-grid" data-entrance>
        <section className="form-panel session-form-panel" id="new-session">
          <div className="section-heading">
            <span><MessagesSquare size={16} /> 创建持续会话</span>
            <Badge variant="outline">本地仓库</Badge>
          </div>
          <p className="workspace-create-safety">直接绑定真实 Git 仓库；Coding 会修改源码。同一仓库仅允许一个活跃会话，不会自动提交或推送。</p>
          <form onSubmit={submit}>
            <label>
              <span>会话名称 <small>可选</small></span>
              <input
                id="session-title"
                aria-invalid={Boolean(apiError?.fieldErrors.title)}
                aria-describedby={apiError?.fieldErrors.title ? 'session-title-error' : undefined}
                value={form.title}
                onChange={(event) => update('title', event.target.value)}
                placeholder="例如：用户模块持续开发"
              />
              {apiError?.fieldErrors.title && <em id="session-title-error">{apiError.fieldErrors.title}</em>}
            </label>
            <label>
              <span>本地 Git 仓库 <b>*</b></span>
              <input
                id="session-repository"
                aria-invalid={Boolean(apiError?.fieldErrors.repository)}
                aria-describedby={apiError?.fieldErrors.repository ? 'session-repository-error' : undefined}
                value={form.repository}
                onChange={(event) => update('repository', event.target.value)}
                placeholder="D:\personal_project\your-spring-project"
                required
              />
              {apiError?.fieldErrors.repository && <em id="session-repository-error">{apiError.fieldErrors.repository}</em>}
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
                <p title={session.sourceRepository}><FolderGit2 size={14} />{session.sourceRepository.split(/[\\/]/).filter(Boolean).at(-1)}</p>
                <div>
                  <time>{formatSessionTime(session.updatedAt)}</time>
                  <span className="session-open-label">打开工作区 <ArrowRight size={13} /></span>
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
    </PageEntrance>
  )
}
