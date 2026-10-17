import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { ApiError } from '../../shared/api/http'
import { readHandoffs, writeHandoff } from '../../shared/api/reviewApi'
import type { HandoffIntent, HandoffStatus, ReportApproval } from '../../shared/api/reviewApi'
import { TechnicalDetails } from '../../shared/ui/surfaces'

const statusText: Record<HandoffStatus, string> = { PREPARED: '交接已准备', SUPERSEDED: '已被新版本替代', REVOKED: '交接已撤销', STARTING: '启动意图待核实', STARTED: '已关联 Coding Turn', START_BLOCKED: '工作区已变化，请重新审查' }

/** Read-only retrieval and explicit actions. An unknown write retains its exact retry identity. */
export function ReviewHandoffPanel({ sessionId, runId, sessionActive, approval }: { sessionId: string; runId: string; sessionActive: boolean; approval: ReportApproval | null }) {
  const key = ['review-handoffs', sessionId, runId]
  const storageKey = `code-weave:handoff-intent:${sessionId}:${runId}`
  const [intent, setIntent] = useState<HandoffIntent | null>(() => {
    try {
      const raw = sessionStorage.getItem(storageKey)
      if (!raw || raw.length > 65536) return null
      const value = JSON.parse(raw)
      return ['prepare', 'start', 'revoke'].includes(value.action) && typeof value.body?.requestId === 'string' ? value as HandoffIntent : null
    } catch { return null }
  })
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(intent ? '有未确认的交接请求，读取历史不会自动启动；请重试原请求。' : null)
  const running = useRef(false)
  const client = useQueryClient()
  const query = useQuery({ queryKey: key, queryFn: () => readHandoffs(sessionId, runId), retry: false })
  async function send(next: HandoffIntent) {
    if (running.current) return
    running.current = true; setPending(true); setError(null); setIntent(next)
    try {
      try { sessionStorage.setItem(storageKey, JSON.stringify(next)) }
      catch { throw new Error('无法保留原请求，本次尚未发送。') }
      const result = await writeHandoff(sessionId, runId, next)
      sessionStorage.removeItem(storageKey); setIntent(null)
      client.setQueryData(key, (old: typeof query.data) => {
        const previous = old ?? []
        return previous.some(v => v.content.handoffId === result.content.handoffId)
          ? previous.map(v => v.content.handoffId === result.content.handoffId ? result : v) : [...previous, result]
      })
      await client.invalidateQueries({ queryKey: ['human-report', sessionId, runId] })
      await client.invalidateQueries({ queryKey: ['coding-session', sessionId] })
      await client.invalidateQueries({ queryKey: ['coding-turns', sessionId] })
    } catch (value) {
      if (value instanceof ApiError && [400, 403, 404, 409, 413, 422].includes(value.status)) {
        sessionStorage.removeItem(storageKey); setIntent(null)
      }
      setError(value instanceof ApiError && value.code === 'HANDOFF_WORKSPACE_DRIFTED'
        ? '工作区已变化，请重新审查；不能强制使用旧交接。' : value instanceof Error ? value.message : '交接未能确认')
    } finally { setPending(false); running.current = false }
  }
  const disabled = pending || intent !== null || !sessionActive
  const current = query.data?.at(-1)
  const sameApproval = current?.content.approvalRequestId === approval?.requestId
  return <section aria-label="修复交接">
    <h4>修复交接与 Coding</h4>
    <p>保存批准选择和准备交接不会启动 Coding；启动前会核对工作区、报告版本和资源。</p>
    {query.isPending && <p>正在读取交接历史…</p>}
    {query.error && <p role="alert">{query.error.message}</p>}
    <button type="button" disabled={disabled || !approval || query.isPending || query.isError || sameApproval} onClick={() => approval && void send({ action: 'prepare', body: { requestId: crypto.randomUUID(), expectedRevision: approval.revision, expectedRevisionDigest: approval.revisionDigest } })}>准备修复交接</button>
    {query.data?.map(view => <article key={view.content.handoffId}>
      <p>报告版本 {view.content.reportRevision} · {statusText[view.lifecycle.status]}</p>
      <TechnicalDetails><p>代码快照：<code>{view.content.snapshotId}</code></p>{view.lifecycle.turnId && <p>关联任务：<code>{view.lifecycle.turnId}</code></p>}</TechnicalDetails>
      {view.content.materials.map(material => <div key={material.review.findingId}>
        <h5>{material.review.title} · 原始等级：{material.review.originalDisposition}</h5>
        <p>{material.review.description}</p><p>{material.review.fixInstructions}</p><p>{material.review.note}</p>
        <details><summary>原始事实、假设与证据</summary><pre>{material.originalFindingJson}</pre>{material.evidenceJson.map((text, index) => <pre key={index}>{text}</pre>)}</details>
      </div>)}
      <p>{view.content.reportInstructions}</p><p>{view.content.approvalInstructions}</p>
      <button type="button" disabled={disabled || view !== current || !sameApproval || view.lifecycle.status !== 'PREPARED'} onClick={() => void send({ action: 'start', handoffId: view.content.handoffId, body: { requestId: crypto.randomUUID(), expectedHandoffDigest: view.content.contentDigest } })}>启动 Coding</button>
      <button type="button" disabled={disabled || !['PREPARED', 'SUPERSEDED', 'START_BLOCKED'].includes(view.lifecycle.status)} onClick={() => void send({ action: 'revoke', handoffId: view.content.handoffId, body: { requestId: crypto.randomUUID(), expectedHandoffDigest: view.content.contentDigest } })}>撤销交接</button>
      {view.lifecycle.turnId && <p>已关联修复任务；失败或中断不会重复启动。</p>}
      {view.lifecycle.status === 'STARTING' && <><p>启动状态待核实，保留原请求；历史读取不会重新发送。</p>
        <button type="button" disabled={disabled || !view.lifecycle.startRequestId} onClick={() => view.lifecycle.startRequestId && void send({ action: 'start', handoffId: view.content.handoffId, body: { requestId: view.lifecycle.startRequestId, expectedHandoffDigest: view.content.contentDigest } })}>核实并重试原启动</button>
      </>}
    </article>)}
    {error && <p role="alert">{error}</p>}
    {intent && <button type="button" disabled={pending || !sessionActive} onClick={() => void send(intent)}>重试原交接请求</button>}
    <button type="button" disabled={pending} onClick={() => void query.refetch()}>读取交接历史</button>
  </section>
}
