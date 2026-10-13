import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { listReviewRuns, readEvidenceMaterials, readReviewResult } from '../../shared/api/reviewApi'

function EvidenceMaterial({ sessionId, runId, evidenceId }: { sessionId: string; runId: string; evidenceId: string }) {
  const [open, setOpen] = useState(false)
  const material = useQuery({ queryKey: ['review-evidence', sessionId, runId, evidenceId], queryFn: () => readEvidenceMaterials(sessionId, runId, evidenceId), enabled: open, retry: false })
  return <div>
    <button type="button" onClick={() => setOpen(!open)}>{open ? '收起固定材料' : '查看固定材料'}</button>
    {open && material.isPending && <p>正在读取材料…</p>}
    {open && material.isError && <p role="alert">{material.error.message}</p>}
    {open && material.data?.materials.map((item, index) => <div key={index}>
      <p>{item.coverage} / {item.redactionState}</p><pre><code>{item.text}</code></pre>
    </div>)}
  </div>
}

/** Read-only imported reports, including closed-session history. */
export function ReviewDraftPanel({ sessionId }: { sessionId: string }) {
  const [runId, setRunId] = useState<string | null>(null)
  const runs = useQuery({ queryKey: ['review-runs', sessionId], queryFn: () => listReviewRuns(sessionId), retry: false })
  const report = useQuery({ queryKey: ['review-result', sessionId, runId], queryFn: () => readReviewResult(sessionId, runId!), enabled: runId !== null, retry: false })
  const value = report.data
  return <section className="session-changes">
    <header><strong>Review 报告草稿</strong><small>有限范围审查，草稿不代表正式裁决或全项目正确性保证。</small></header>
    {runs.isPending && <p>正在读取审查历史…</p>}
    {runs.isError && <p role="alert">{runs.error.message}</p>}
    {runs.data?.length === 0 && <p>暂无审查记录。</p>}
    {runs.data?.map(run => <p key={run.runId}>
      <button type="button" disabled={run.status !== 'COMPLETED'} onClick={() => setRunId(run.runId)}>{run.simulationOnly ? '模拟' : '真实'} · {run.status} · {run.runId}</button>
      {run.diagnosticCode && <span>{run.diagnosticCode}</span>}
    </p>)}
    {runId && report.isPending && <p>正在读取固定报告…</p>}
    {report.isError && <p role="alert">{report.error.message}</p>}
    {value && <article>
      <p>Snapshot：<code>{value.snapshotId}</code></p>
      {value.resultVersion === '1.0' ? <p>模拟执行结果，不代表代码审查通过。</p> : <>
        {value.passResults.map((pass, index) => <div key={index}>
          <strong>{pass.status} / {pass.coverage}</strong>
          <p>检查范围：{pass.checkedScope.join('、')}</p>
          <ul>{[...pass.limitations, ...pass.incompleteReasons].map((text, i) => <li key={i}>{text}</li>)}</ul>
        </div>)}
        {value.findings.map(finding => <div key={finding.findingId}>
          <h3>{finding.title}</h3><p>{finding.disposition} / {finding.severity} · 置信度 {finding.confidence}</p>
          <p>{finding.claim}</p><p>Evidence：{finding.evidenceIds.join('、')}</p>
        </div>)}
        <details><summary>来源材料与覆盖</summary>{value.evidence.map(item => <div key={item.evidenceId}>
          <p><code>{item.evidenceId}</code> · {item.evidenceType} / {item.coverage}</p><p>{item.summary}</p>
          <ul>{item.sourceRefs.map((ref, i) => <li key={i}>{ref.sourceType === 'CODE'
            ? `${ref.path} (${ref.side}, ${ref.startLine}–${ref.endLine})`
            : ref.sourceType === 'ARTIFACT' ? `Artifact ${ref.artifactId} (${ref.startLine}–${ref.endLine})` : `原始用户输入 ${ref.sha256}`}</li>)}</ul>
          <EvidenceMaterial key={`${runId}:${item.evidenceId}`} sessionId={sessionId} runId={runId!} evidenceId={item.evidenceId} />
        </div>)}</details>
        <div className="turn-markdown"><ReactMarkdown skipHtml remarkPlugins={[remarkGfm]} components={{ img: () => null }}>{value.reportDraft.markdown}</ReactMarkdown></div>
      </>}
    </article>}
  </section>
}
