import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { ApiError } from '../../shared/api/http'
import { readHumanReport, writeHumanReport } from '../../shared/api/reviewApi'
import type { FindingDecision, FindingReview, ReportIntent, ReportView } from '../../shared/api/reviewApi'

const reportMessages: Record<string, string> = {
  REPORT_REVISION_CONFLICT: '报告已更新，请读取当前版本后再修改。',
  REPORT_REQUEST_CONFLICT: '请求标识已用于不同内容，请核对原请求。',
  REPORT_APPROVAL_INVALID: '请明确选择已经批准修改的问题，不能提交空选择。',
  REPORT_INCOMPLETE_ACK_REQUIRED: '请先确认报告的有限或不完整覆盖。',
  REPORT_FINDING_INVALID: '只能修订原报告的问题，不能改变来源或原始等级。',
  REPORT_CLOSED: '人工审阅已关闭，历史仅供读取。',
  REPORT_WRITE_UNCERTAIN: '版本发布未确认，请保留并重试原请求。',
  REVIEW_STATE_WRITE_UNCERTAIN: '写入未确认，请核对当前版本或重试同一请求。',
}

const decisions: Record<FindingDecision, string> = { PENDING: '待裁决', APPROVED_FOR_FIX: '批准修改', DEFERRED: '暂缓', ACCEPTED_RISK: '接受风险', DISMISSED: '不采纳' }

function RevisionForm({ view, disabled, requiresAcknowledgement, onIntent }: { view: ReportView; disabled: boolean; requiresAcknowledgement: boolean; onIntent: (intent: ReportIntent) => void }) {
  const revision = view.reportRevision
  const [reviews, setReviews] = useState<FindingReview[]>(revision.findingReviews)
  const [instructions, setInstructions] = useState(revision.userInstructions)
  const [selected, setSelected] = useState<string[]>([])
  const [acknowledged, setAcknowledged] = useState(false)
  const readOnly = disabled || !view.editable
  const dirty = JSON.stringify(reviews) !== JSON.stringify(revision.findingReviews) || instructions !== revision.userInstructions
  function update(index: number, field: keyof FindingReview, value: string) {
    setReviews(previous => previous.map((item, i) => i === index ? { ...item, [field]: value } : item))
  }
  return <div>
    <p>报告版本 {revision.revision} · 原 Result {revision.sourceResultId} · Snapshot {revision.snapshotId}</p>
    <small>人工修订保留原始等级、证据和假设。原报告仍可在下方查看。</small>
    {reviews.length === 0 && <p>没有可批准的 Finding，可关闭本次人工审阅。</p>}
    {reviews.map((item, index) => <fieldset key={item.findingId} disabled={readOnly}>
      <legend>问题 {index + 1} · 原始等级：{item.originalDisposition}</legend>
      <label>标题 {index + 1}<input aria-label={`标题 ${index + 1}`} value={item.title} onChange={e => update(index, 'title', e.target.value)} /></label>
      <label>描述 {index + 1}<textarea aria-label={`描述 ${index + 1}`} value={item.description} onChange={e => update(index, 'description', e.target.value)} /></label>
      <label>修改要求 {index + 1}<textarea aria-label={`修改要求 ${index + 1}`} value={item.fixInstructions} onChange={e => update(index, 'fixInstructions', e.target.value)} /></label>
      <label>备注 {index + 1}<textarea aria-label={`备注 ${index + 1}`} value={item.note} onChange={e => update(index, 'note', e.target.value)} /></label>
      <label>裁决 {index + 1}<select aria-label={`裁决 ${index + 1}`} value={item.decision} onChange={e => update(index, 'decision', e.target.value)}>
        {Object.entries(decisions).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
      </select></label>
    </fieldset>)}
    <label>用户要求<textarea aria-label="用户要求" disabled={readOnly} value={instructions} onChange={e => setInstructions(e.target.value)} /></label>
    <button type="button" disabled={readOnly || !dirty} onClick={() => onIntent({ action: 'edit', body: { expectedRevision: revision.revision, requestId: crypto.randomUUID(), findingReviews: reviews, userInstructions: instructions } })}>保存为新版本</button>
    {revision.findingReviews.filter(f => f.decision === 'APPROVED_FOR_FIX').map(item => <label key={item.findingId}>
      <input type="checkbox" disabled={readOnly || dirty} checked={selected.includes(item.findingId)} onChange={e => setSelected(previous => e.target.checked ? [...previous, item.findingId] : previous.filter(id => id !== item.findingId))} />
      {`选择交付：${item.title}`}
    </label>)}
    {requiresAcknowledgement && <label><input type="checkbox" disabled={readOnly || dirty} checked={acknowledged} onChange={e => setAcknowledged(e.target.checked)} />我确认本报告只有有限或不完整覆盖</label>}
    <button type="button" disabled={readOnly || dirty || selected.length === 0 || requiresAcknowledgement && !acknowledged} onClick={() => onIntent({ action: 'approve', body: { expectedRevision: revision.revision, expectedRevisionDigest: revision.revisionDigest, requestId: crypto.randomUUID(), selectedFindingIds: selected, incompleteAcknowledged: acknowledged, userInstructions: instructions } })}>保存批准选择</button>
    <button type="button" disabled={readOnly} onClick={() => onIntent({ action: 'close', body: { expectedRevision: revision.revision, requestId: crypto.randomUUID() } })}>关闭人工审阅</button>
    {view.approval && <p>已保存版本 {view.approval.revision} 的批准选择，Coding 需后续显式启动。</p>}
    {!view.editable && <p>当前显示只读历史，或人工审阅已关闭。</p>}
  </div>
}

export function ReviewReportEditor({ sessionId, runId, sessionActive, requiresAcknowledgement = true }: { sessionId: string; runId: string; sessionActive: boolean; requiresAcknowledgement?: boolean }) {
  const [selectedRevision, setSelectedRevision] = useState<number | undefined>()
  const storageKey = `code-weave:report-intent:${sessionId}:${runId}`
  const [intent, setIntent] = useState<ReportIntent | null>(() => {
    try {
      const raw = sessionStorage.getItem(storageKey)
      if (!raw || raw.length > 2 * 1024 * 1024) return null
      const value = JSON.parse(raw)
      return ['create', 'edit', 'approve', 'close'].includes(value.action) && typeof value.body?.requestId === 'string' ? value as ReportIntent : null
    } catch { return null }
  })
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<Error | null>(() => intent ? new Error('有未确认的报告请求，请核对当前版本或重试同一请求。') : null)
  const running = useRef(false)
  const queryClient = useQueryClient()
  const key = ['human-report', sessionId, runId]
  const report = useQuery({ queryKey: [...key, selectedRevision], queryFn: () => readHumanReport(sessionId, runId, selectedRevision), retry: false })
  async function send(next: ReportIntent) {
    if (running.current) return
    running.current = true; setPending(true); setIntent(next); setError(null)
    try {
      // Retain identity across navigation/reload; a restored intent is only sent on explicit retry.
      try { sessionStorage.setItem(storageKey, JSON.stringify(next)) }
      catch { throw new Error('无法保留重试请求，本次尚未发送。') }
      await writeHumanReport(sessionId, runId, next)
      sessionStorage.removeItem(storageKey)
      setIntent(null); setSelectedRevision(undefined)
      await queryClient.invalidateQueries({ queryKey: key })
    } catch (value) {
      if (value instanceof ApiError && [400, 403, 404, 409, 413, 422].includes(value.status)) {
        sessionStorage.removeItem(storageKey); setIntent(null)
      }
      setError(value instanceof Error ? value : new Error('报告保存未能确认'))
    }
    finally { setPending(false); running.current = false }
  }
  async function readCurrent() {
    try {
      const current = await queryClient.fetchQuery({ queryKey: [...key, undefined], queryFn: () => readHumanReport(sessionId, runId), staleTime: 0 })
      setSelectedRevision(undefined)
      const confirmed = intent && (current.reportRevision.requestId === intent.body.requestId || current.approval?.requestId === intent.body.requestId)
      // Conflict is confirmed rejection. Other uncertain writes retain the exact retry identity.
      if (confirmed || error instanceof ApiError && error.status === 409) { sessionStorage.removeItem(storageKey); setIntent(null); setError(null) }
    } catch (value) { if (!intent) setError(value instanceof Error ? value : new Error('读取报告失败')) }
  }
  const absent = report.error instanceof ApiError && report.error.code === 'REPORT_NOT_FOUND'
  return <section aria-label="人工报告与版本">
    <h3>人工报告与版本</h3>
    {report.isPending && <p>正在读取报告版本…</p>}
    {absent && <><p>尚未开始人工审阅。</p><button type="button" disabled={!sessionActive || pending || intent !== null} onClick={() => void send({ action: 'create', body: { requestId: crypto.randomUUID() } })}>开始人工审阅</button></>}
    {report.error && !absent && <p role="alert">{report.error.message}</p>}
    {error && <div role="alert"><p>{error instanceof ApiError ? reportMessages[error.code] ?? error.message : error.message}</p><button type="button" disabled={pending || !sessionActive || intent === null} onClick={() => intent && void send(intent)}>重试同一请求</button>
      <button type="button" disabled={pending} onClick={() => void readCurrent()}>读取当前版本</button></div>}
    {report.data && <>
      <label>查看版本<select aria-label="查看版本" disabled={pending || intent !== null} value={report.data.reportRevision.revision} onChange={e => setSelectedRevision(Number(e.target.value))}>
        {report.data.revisionHistory.map(item => <option key={item.revision} value={item.revision}>{item.revision} · {item.createdAt}</option>)}
      </select></label>
      <RevisionForm key={report.data.reportRevision.revisionDigest} view={{ ...report.data, editable: report.data.editable && sessionActive }} disabled={pending || intent !== null} requiresAcknowledgement={requiresAcknowledgement} onIntent={next => void send(next)} />
    </>}
  </section>
}
