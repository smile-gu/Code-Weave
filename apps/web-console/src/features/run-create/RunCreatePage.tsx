import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { ArrowRight, FolderGit2, Info, ShieldCheck } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { ApiError, createRun, getRuntimeReadiness } from '../../shared/api/runApi'
import { RuntimeReadinessPanel } from './RuntimeReadinessPanel'

type FormState = {
  repository: string
  baseRef: string
  taskPrompt: string
  acceptanceCriteria: string
  allowedPaths: string
  deniedPaths: string
}

const initialForm: FormState = {
  repository: '',
  baseRef: 'HEAD',
  taskPrompt: '',
  acceptanceCriteria: 'Maven 编译和测试通过',
  allowedPaths: 'src/**\npom.xml',
  deniedPaths: '.git/**\ntarget/**',
}

/** 将多行输入整理为后端接受的非空规则数组。 */
function lines(value: string) {
  return value.split(/\r?\n/).map((item) => item.trim()).filter(Boolean)
}

/** 提供本地仓库 Coding Run 的创建表单。 */
export function RunCreatePage() {
  const navigate = useNavigate()
  const [form, setForm] = useState(initialForm)
  const readinessQuery = useQuery({
    queryKey: ['runtime-readiness'],
    queryFn: getRuntimeReadiness,
    retry: false,
    refetchOnWindowFocus: false,
  })
  const mutation = useMutation({
    mutationFn: createRun,
    onSuccess: (response) => navigate(`/runs/${response.runId}`),
  })

  const update = (field: keyof FormState, value: string) => {
    setForm((current) => ({ ...current, [field]: value }))
  }
  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    mutation.mutate({
      repository: form.repository.trim(),
      baseRef: form.baseRef.trim(),
      taskPrompt: form.taskPrompt.trim(),
      acceptanceCriteria: lines(form.acceptanceCriteria),
      allowedPaths: lines(form.allowedPaths),
      deniedPaths: lines(form.deniedPaths),
    })
  }
  const apiError = mutation.error instanceof ApiError ? mutation.error : null
  const runtimeReady = readinessQuery.data?.status === 'READY'

  return (
    <div className="create-layout">
      <section className="create-intro">
        <p className="eyebrow">NEW CODING RUN</p>
        <h1>让修改过程<br />从一开始就可见</h1>
        <p className="intro-copy">
          Code Weave 会在独立 Git Worktree 中运行 Claude Code，随后采集变更、检查路径边界并执行 Maven 验证。
        </p>
        <div className="boundary-note">
          <Info size={18} />
          <div>
            <strong>首版 Web 边界</strong>
            <p>浏览器不能向后端提供本机文件夹绝对路径，请手动粘贴仓库路径。原始分支不会被直接修改。</p>
          </div>
        </div>
        <ul className="assurance-list">
          <li><FolderGit2 size={17} /> 每次任务创建独立 Worktree</li>
          <li><ShieldCheck size={17} /> 路径门禁阻止越权修改</li>
        </ul>
      </section>

      <section className="form-panel">
        <RuntimeReadinessPanel
          readiness={readinessQuery.data}
          loading={readinessQuery.isLoading}
          refreshing={readinessQuery.isFetching && !readinessQuery.isLoading}
          error={readinessQuery.isError ? readinessQuery.error.message : null}
          onRefresh={() => { void readinessQuery.refetch() }}
        />
        <div className="section-heading">
          <span>任务配置</span>
          <small>所有字段只服务于本次 Run</small>
        </div>
        <form onSubmit={submit}>
          <label>
            <span>本地 Git 仓库 <b>*</b></span>
            <input
              autoFocus
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
              <span>执行范围</span>
              <input value="Spring Boot 3.x · Maven" disabled />
            </label>
          </div>

          <label>
            <span>Coding 任务说明 <b>*</b></span>
            <textarea
              className="task-input"
              value={form.taskPrompt}
              onChange={(event) => update('taskPrompt', event.target.value)}
              placeholder="说明需要解决的问题、业务背景和期望行为…"
              required
            />
            {apiError?.fieldErrors.taskPrompt && <em>{apiError.fieldErrors.taskPrompt}</em>}
          </label>

          <label>
            <span>验收标准 <small>每行一条</small></span>
            <textarea value={form.acceptanceCriteria} onChange={(event) => update('acceptanceCriteria', event.target.value)} />
          </label>

          <div className="field-row">
            <label>
              <span>允许修改路径 <small>每行一条</small></span>
              <textarea value={form.allowedPaths} onChange={(event) => update('allowedPaths', event.target.value)} />
              {apiError?.fieldErrors.allowedPaths && <em>{apiError.fieldErrors.allowedPaths}</em>}
            </label>
            <label>
              <span>禁止修改路径 <small>每行一条</small></span>
              <textarea value={form.deniedPaths} onChange={(event) => update('deniedPaths', event.target.value)} />
            </label>
          </div>

          {mutation.isError && (
            <div className="form-error" role="alert">
              <strong>{apiError?.code ?? 'SUBMIT_FAILED'}</strong>
              <span>{mutation.error.message || '任务提交失败，请确认 Control Plane 已启动。'}</span>
            </div>
          )}

          <button className="primary-button" disabled={mutation.isPending || !runtimeReady} type="submit">
            {mutation.isPending
              ? '正在提交…'
              : readinessQuery.isLoading
                ? '正在检查运行环境…'
                : runtimeReady ? '创建 Coding Run' : '运行环境未就绪'}
            {!mutation.isPending && runtimeReady && <ArrowRight size={17} />}
          </button>
        </form>
      </section>
    </div>
  )
}
