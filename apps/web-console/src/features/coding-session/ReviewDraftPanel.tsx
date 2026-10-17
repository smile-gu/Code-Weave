import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { ReviewReportEditor } from './ReviewReportEditor'
import { ReviewStartPanel } from './ReviewStartPanel'
import { reviewLabel } from './reviewLabels'
import { listReviewRuns, readEvidenceMaterials, readReviewResult } from '../../shared/api/reviewApi'

const passNames: Record<string, string> = { correctness: '正确性', consistency: '一致性', security: '安全', performance: '性能', architecture: '架构', testing: '测试' }

function EvidenceMaterial({ sessionId, runId, evidenceId }: { sessionId: string; runId: string; evidenceId: string }) {
  const [open, setOpen] = useState(false)
  const material = useQuery({ queryKey: ['review-evidence', sessionId, runId, evidenceId], queryFn: () => readEvidenceMaterials(sessionId, runId, evidenceId), enabled: open, retry: false })
  return <div>
    <button type="button" onClick={() => setOpen(!open)}>{open ? '收起固定材料' : '查看固定材料'}</button>
    {open && material.isPending && <p>正在读取材料…</p>}
    {open && material.isError && <p role="alert">{material.error.message}</p>}
    {open && material.data?.materials.map((item, index) => <div key={index}>
      <p>{reviewLabel(item.coverage)} / {reviewLabel(item.redactionState)}</p><pre><code>{item.text}</code></pre>
    </div>)}
  </div>
}

/** Read-only imported reports, including closed-session history. */
export function ReviewDraftPanel({ sessionId, sessionActive = true, codingActive = false }: { sessionId: string; sessionActive?: boolean; codingActive?: boolean }) {
  const [editing, setEditing] = useState(false)
  const [runId, setRunId] = useState<string | null>(null)
  const runs = useQuery({ queryKey: ['review-runs', sessionId], queryFn: () => listReviewRuns(sessionId), retry: false,
    refetchInterval: query => query.state.data?.some(run => !['COMPLETED', 'FAILED', 'CANCELLED'].includes(run.status)) ? 2000 : false })
  const report = useQuery({ queryKey: ['review-result', sessionId, runId], queryFn: () => readReviewResult(sessionId, runId!), enabled: runId !== null, retry: false })
  const value = report.data
  return <section className="session-changes review-workspace">
    <header><strong>Review 报告草稿</strong><small>有限范围审查，草稿不代表正式裁决或全项目正确性保证。</small></header>
    <ReviewStartPanel sessionId={sessionId} blocked={!sessionActive || codingActive || !runs.data || runs.data.some(run => !['COMPLETED', 'FAILED', 'CANCELLED'].includes(run.status))} onStarted={() => { void runs.refetch() }} />
    <button type="button" onClick={() => { void runs.refetch() }} disabled={runs.isFetching}>刷新审查历史</button>
    {runs.isPending && <p>正在读取审查历史…</p>}
    {runs.isError && <p role="alert">{runs.error.message}</p>}
    {runs.data?.length === 0 && <div className="workspace-empty-note"><strong>暂无审查记录。</strong><p>这里展示固定 Snapshot 的独立审查结果，不以 Coding 的自述代替审查。</p></div>}
    <div className="review-run-list">{runs.data?.map(run => <div className="review-run-row" key={run.runId}>
      <button type="button" aria-pressed={runId === run.runId} disabled={run.status !== 'COMPLETED'} onClick={() => { setRunId(run.runId); setEditing(false) }}>{run.simulationOnly ? '模拟' : '真实'} · {reviewLabel(run.status)} · {run.runId}</button>
      {run.diagnosticCode && <span title={run.diagnosticCode}>{reviewLabel(run.diagnosticCode)}</span>}
      {run.passProgress?.map(pass => <span key={pass.passId} title={pass.reason ?? pass.status}> · {passNames[pass.passId] ?? pass.passId}：{reviewLabel(pass.status)}{pass.reason && `（${reviewLabel(pass.reason)}）`}</span>)}
    </div>)}</div>
    {!runId && Boolean(runs.data?.length) && <p className="workspace-empty-note">选择一次已完成的审查，查看问题、证据与人工报告。</p>}
    {runId && report.isPending && <p>正在读取固定报告…</p>}
    {report.isError && <p role="alert">{report.error.message}</p>}
    {value && <article className="review-result-body">
      <div className="review-result-context"><span>固定 Snapshot</span><code>{value.snapshotId}</code><span>{value.findings.length} 个问题 · {value.evidence.length} 份证据</span></div>
      {value.resultVersion === '1.0' ? <p>模拟执行结果，不代表代码审查通过。</p> : <>
        <button type="button" onClick={() => setEditing(previous => !previous)}>人工报告与版本</button>
        <p className="workspace-empty-note">后续报告默认使用中文；已生成的历史报告保留原文，可通过人工报告修订。</p>
        {editing && <ReviewReportEditor key={runId} sessionId={sessionId} runId={runId!} sessionActive={sessionActive} requiresAcknowledgement={value.resultVersion !== '3.0' || value.coverageStatus !== 'SELECTED_COMPLETE' || value.reviewPlan?.selectedPasses.length !== 6} />}
        {value.resultVersion === '3.0' && <p>所选维度：{value.reviewPlan?.selectedPasses.map(pass => passNames[pass] ?? pass).join('、')}。{value.coverageStatus === 'SELECTED_COMPLETE' ? '所选有限范围已完成，不代表六维或全项目无问题。' : '所选维度尚未完成。'}</p>}
        <div className="review-pass-grid">{value.passResults.map((pass, index) => <div className="review-pass-card" key={index}>
          <strong title={`${pass.status} / ${pass.coverage}`}>{pass.passId && `${passNames[pass.passId] ?? pass.passId} · `}{reviewLabel(pass.status)} / {reviewLabel(pass.coverage)}</strong>
          {pass.skipReason && <p title={pass.skipReason}>{reviewLabel(pass.skipReason)}</p>}
          <p>检查范围：{pass.checkedScope.join('、')}</p>
          {Boolean(pass.limitations.length || pass.incompleteReasons.length) && <details className="review-pass-limitations"><summary>范围与限制（{pass.limitations.length + pass.incompleteReasons.length}）</summary><ul>{[...pass.limitations, ...pass.incompleteReasons].map((text, i) => <li key={i}>{reviewLabel(text)}</li>)}</ul></details>}
        </div>)}</div>
        <div className="review-findings">{value.findings.map(finding => <details className="review-finding-card" key={finding.findingId}>
          <summary><span title={finding.severity} className={`finding-grade grade-${finding.severity.toLowerCase()}`}>{reviewLabel(finding.severity)}</span><strong>{finding.title}</strong><span title={finding.disposition} className="finding-disposition">{reviewLabel(finding.disposition)}</span></summary><div className="review-finding-body"><p>{reviewLabel(finding.disposition)} / {reviewLabel(finding.severity)} · 置信度 {finding.confidence}</p>
          <p>{finding.claim}</p><p>证据：{finding.evidenceIds.join('、')}</p>
          {finding.contributions && <p>贡献维度：{finding.contributions.map(c => `${passNames[c.passId] ?? c.passId}（${reviewLabel(c.disposition)}）`).join('、')}。保留原始等级和来源。</p>}
        </div></details>)}</div>
        <details className="review-source-details"><summary>来源材料与覆盖</summary>{value.evidence.map(item => <div key={item.evidenceId}>
          <p><code>{item.evidenceId}</code> · {reviewLabel(item.evidenceType)} / {reviewLabel(item.coverage)}</p><p>{item.summary}</p>
          <ul>{item.sourceRefs.map((ref, i) => <li key={i}>{ref.sourceType === 'CODE'
            ? `${ref.path}（${reviewLabel(ref.side)}，第 ${ref.startLine}–${ref.endLine} 行）`
            : ref.sourceType === 'ARTIFACT' ? `材料 ${ref.artifactId}（第 ${ref.startLine}–${ref.endLine} 行）` : `原始用户输入 ${ref.sha256}`}</li>)}</ul>
          <EvidenceMaterial key={`${runId}:${item.evidenceId}`} sessionId={sessionId} runId={runId!} evidenceId={item.evidenceId} />
        </div>)}</details>
        <details className="review-source-details"><summary>原始 Markdown 报告</summary><div className="turn-markdown"><ReactMarkdown skipHtml remarkPlugins={[remarkGfm]} components={{ img: () => null }}>{value.reportDraft.markdown}</ReactMarkdown></div></details>
      </>}
    </article>}
  </section>
}
