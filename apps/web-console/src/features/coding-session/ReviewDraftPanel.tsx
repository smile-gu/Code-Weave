import { useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { ReviewReportEditor } from './ReviewReportEditor'
import { ReviewStartPanel } from './ReviewStartPanel'
import { reviewLabel } from './reviewLabels'
import { listReviewRuns, readEvidenceMaterials, readReviewResult } from '../../shared/api/reviewApi'
import { FileSearch, RefreshCw, Search, ShieldCheck, AlertCircle, ChevronRight } from 'lucide-react'
import { Badge, Card, CardContent, CardHeader, Skeleton, TechnicalDetails } from '../../shared/ui/surfaces'

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
  const [search, setSearch] = useState('')
  const [severity, setSeverity] = useState('ALL')
  const runs = useQuery({ queryKey: ['review-runs', sessionId], queryFn: () => listReviewRuns(sessionId), retry: false,
    refetchInterval: query => query.state.data?.some(run => !['COMPLETED', 'FAILED', 'CANCELLED'].includes(run.status)) ? 2000 : false })
  const selectedRunId = runId ?? runs.data?.find(run => run.status === 'COMPLETED')?.runId ?? null
  // Pin the first report once selected: a new completed run must not replace an open edit.
  useEffect(() => { if (runId === null && selectedRunId !== null) setRunId(selectedRunId) }, [runId, selectedRunId])
  const report = useQuery({ queryKey: ['review-result', sessionId, selectedRunId], queryFn: () => readReviewResult(sessionId, selectedRunId!), enabled: selectedRunId !== null, retry: false })
  const value = report.data
  const findings = value?.findings.filter(f => (severity === 'ALL' || f.severity === severity) && `${f.title} ${f.claim}`.toLocaleLowerCase().includes(search.toLocaleLowerCase())) ?? []
  return <section className="session-changes review-workspace">
    <header><div><strong>Review 报告草稿</strong><p>查看审查进度、核对问题，并决定下一步修复。</p></div><Badge variant="outline"><ShieldCheck size={13} />独立审查</Badge></header>
    <ReviewStartPanel sessionId={sessionId} blocked={!sessionActive || codingActive || !runs.data || runs.data.some(run => !['COMPLETED', 'FAILED', 'CANCELLED'].includes(run.status))} onStarted={() => { void runs.refetch() }} />
    <div className="review-history-heading"><h3>审查历史</h3><button type="button" onClick={() => { void runs.refetch() }} disabled={runs.isFetching}><RefreshCw size={14} className={runs.isFetching ? 'is-spinning' : ''} />刷新审查历史</button></div>
    {runs.isPending && <div role="status" aria-label="正在读取审查历史"><Skeleton className="history-skeleton" /><Skeleton className="history-skeleton" /></div>}
    {runs.isError && <p role="alert">{runs.error.message}</p>}
    {runs.data?.length === 0 && <div className="workspace-empty-note"><FileSearch size={30} /><strong>暂无审查记录。</strong><p>从上方预览变更范围，开始第一次代码审查。</p></div>}
    <div className="review-layout">
    {!!runs.data?.length && <aside className="review-history-rail" aria-label="审查记录"><div className="review-run-list">{runs.data.map((run, index) => <div className={`review-run-row ${selectedRunId === run.runId ? 'selected' : ''}`} key={run.runId}>
      <button type="button" aria-pressed={selectedRunId === run.runId} disabled={run.status !== 'COMPLETED'} onClick={() => { if (selectedRunId === run.runId) return; setRunId(run.runId); setEditing(false); setSearch(''); setSeverity('ALL') }}><span className="review-run-name"><FileSearch size={16} />审查 {runs.data!.length - index}<ChevronRight size={14} /></span><span className="review-run-meta">{run.simulationOnly ? '模拟运行' : '代码审查'}<Badge variant={run.status === 'FAILED' ? 'destructive' : 'secondary'}>{reviewLabel(run.status)}</Badge></span></button>
      {run.diagnosticCode && <p className="review-run-diagnostic" title={run.diagnosticCode}><AlertCircle size={13} />{reviewLabel(run.diagnosticCode)}</p>}
      <details className="run-progress-details"><summary>维度进度与详情</summary>{run.passProgress?.map(pass => <p key={pass.passId} title={pass.reason ?? pass.status}>{passNames[pass.passId] ?? pass.passId}：{reviewLabel(pass.status)}{pass.reason && `（${reviewLabel(pass.reason)}）`}</p>)}<TechnicalDetails><p>审查标识：<code>{run.runId}</code></p></TechnicalDetails></details>
    </div>)}</div></aside>}
    <div className="review-report-column">
    {!selectedRunId && Boolean(runs.data?.length) && <div className="workspace-empty-note"><FileSearch size={30} /><strong>报告尚未生成</strong><p>可在左侧查看进度。完成后的审查报告将在这里展示。</p></div>}
    {selectedRunId && report.isPending && <div role="status" aria-label="正在读取固定报告"><Skeleton className="report-skeleton" /><Skeleton className="report-skeleton" /></div>}
    {report.isError && <p role="alert">{report.error.message}</p>}
    {value && <article className="review-result-body">
      <div className="review-report-heading"><div><span className="eyebrow">审查结果</span><h3>代码审查报告</h3></div><TechnicalDetails><dl><div><dt>代码快照</dt><dd><code>{value.snapshotId}</code></dd></div><div><dt>审查标识</dt><dd><code>{selectedRunId}</code></dd></div></dl></TechnicalDetails></div>
      {value.resultVersion === '1.0' ? <p>模拟执行结果，不代表代码审查通过。</p> : <>
        <div className="report-metrics">{[{ label: '发现问题', count: value.findings.length }, { label: '已验证', count: value.findings.filter(f => f.disposition === 'VERIFIED').length }, { label: '来源证据', count: value.evidence.length }].map(metric => <Card key={metric.label}><CardHeader>{metric.label}</CardHeader><CardContent><strong>{metric.count}</strong></CardContent></Card>)}</div>
        <div className="report-actions"><p>核对问题后，可人工修订、批准并准备修复交接。</p><button type="button" aria-expanded={editing} onClick={() => setEditing(previous => !previous)}>人工报告与版本</button></div>
        {editing && <ReviewReportEditor key={selectedRunId} sessionId={sessionId} runId={selectedRunId!} sessionActive={sessionActive} requiresAcknowledgement={value.resultVersion !== '3.0' || value.coverageStatus !== 'SELECTED_COMPLETE' || value.reviewPlan?.selectedPasses.length !== 6} />}
        <div className="report-coverage-note"><ShieldCheck size={16} /><p>{value.resultVersion === '3.0' && <>所选维度：{value.reviewPlan?.selectedPasses.map(pass => passNames[pass] ?? pass).join('、')}。{value.coverageStatus === 'SELECTED_COMPLETE' ? '所选有限范围已完成，不代表六维或全项目无问题。' : '所选维度尚未完成。'}</>}本报告仅覆盖已检查的代码范围，需人工核对。</p></div>
        <div className="review-pass-grid">{value.passResults.map((pass, index) => <div className="review-pass-card" key={index}>
          <strong title={`${pass.status} / ${pass.coverage}`}>{pass.passId && `${passNames[pass.passId] ?? pass.passId} · `}{reviewLabel(pass.status)} / {reviewLabel(pass.coverage)}</strong>
          {pass.skipReason && <p title={pass.skipReason}>{reviewLabel(pass.skipReason)}</p>}
          <details className="review-pass-limitations"><summary>检查范围 · {pass.checkedScope.length} 项</summary><p>{pass.checkedScope.join('、') || '未报告已检查范围'}</p></details>
          {Boolean(pass.limitations.length || pass.incompleteReasons.length) && <details className="review-pass-limitations"><summary>范围与限制（{pass.limitations.length + pass.incompleteReasons.length}）</summary><ul>{[...pass.limitations, ...pass.incompleteReasons].map((text, i) => <li key={i}>{reviewLabel(text)}</li>)}</ul></details>}
        </div>)}</div>
        <div className="findings-heading"><h3>问题列表 <Badge>{value.findings.length}</Badge></h3><div className="finding-filters"><label className="finding-search"><Search size={15} /><input aria-label="搜索审查问题" placeholder="搜索问题" value={search} onChange={e => setSearch(e.target.value)} /></label><select aria-label="筛选严重程度" value={severity} onChange={e => setSeverity(e.target.value)}><option value="ALL">全部级别</option>{Array.from(new Set(value.findings.map(f => f.severity))).map(level => <option key={level} value={level}>{reviewLabel(level)}</option>)}</select></div></div>
        <div className="review-findings">{findings.map(finding => <details className="review-finding-card" key={finding.findingId}>
          <summary><span title={finding.severity} className={`finding-grade grade-${finding.severity.toLowerCase()}`}>{reviewLabel(finding.severity)}</span><strong>{finding.title}</strong><span title={finding.disposition} className="finding-disposition">{reviewLabel(finding.disposition)}</span></summary><div className="review-finding-body">
          <p>{finding.claim}</p>
          {!!finding.codeReferences?.length && <div className="finding-locations">{finding.codeReferences.map((ref, i) => <p key={i}><code>{ref.path}</code><span>第 {ref.startLine}–{ref.endLine} 行 · {reviewLabel(ref.side)}</span></p>)}</div>}
          <p>关联证据：{finding.evidenceIds.map(id => { const index = value.evidence.findIndex(e => e.evidenceId === id); return index >= 0 ? `材料 ${index + 1}` : '未找到关联材料' }).join('、')}</p>
          {finding.contributions && <p>贡献维度：{finding.contributions.map(c => `${passNames[c.passId] ?? c.passId}（${reviewLabel(c.disposition)}）`).join('、')}。保留原始等级和来源。</p>}
          <TechnicalDetails><p>问题标识：<code>{finding.findingId}</code></p><p>模型自报置信度：{finding.confidence}（不代表正确概率）</p></TechnicalDetails>
        </div></details>)}</div>
        {findings.length === 0 && <div className="workspace-empty-note"><FileSearch size={26} /><strong>{value.findings.length ? '没有匹配的问题' : '本次审查未报告问题'}</strong><p>{value.findings.length ? '尝试其他关键词或级别。' : '这不代表代码没有缺陷，请结合审查范围判断。'}</p></div>}
        <details className="review-source-details"><summary>来源材料与覆盖</summary>{value.evidence.map((item, index) => <div key={item.evidenceId}>
          <p><Badge variant="outline">材料 {index + 1}</Badge> · {reviewLabel(item.evidenceType)} / {reviewLabel(item.coverage)}</p><p>{item.summary}</p>
          <ul>{item.sourceRefs.map((ref, i) => <li key={i}>{ref.sourceType === 'CODE'
            ? `${ref.path}（${reviewLabel(ref.side)}，第 ${ref.startLine}–${ref.endLine} 行）`
            : ref.sourceType === 'ARTIFACT' ? `工具材料（第 ${ref.startLine}–${ref.endLine} 行）` : '原始用户输入'}</li>)}</ul>
          <EvidenceMaterial key={`${selectedRunId}:${item.evidenceId}`} sessionId={sessionId} runId={selectedRunId!} evidenceId={item.evidenceId} />
          <TechnicalDetails><p>证据标识：<code>{item.evidenceId}</code></p><pre>{JSON.stringify(item.sourceRefs, null, 2)}</pre></TechnicalDetails>
        </div>)}</details>
        <details className="review-source-details"><summary>原始 Markdown 报告</summary><p>历史报告保留原文；可通过人工报告修订。</p><div className="turn-markdown"><ReactMarkdown skipHtml remarkPlugins={[remarkGfm]} components={{ img: () => null }}>{value.reportDraft.markdown}</ReactMarkdown></div></details>
      </>}
    </article>}
    </div></div>
  </section>
}
