import { useEffect, useMemo, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ArrowLeft, CircleDot, Radio, RotateCcw } from 'lucide-react'
import { Link, useParams } from 'react-router-dom'
import { getRunChanges, getRunResult, getRunStatus } from '../../shared/api/runApi'
import type { RunExecutionStatus } from '../../shared/api/types'
import { DiffPanel } from '../run-result/DiffPanel'
import { RunResultPanel } from '../run-result/RunResultPanel'
import { EventTimeline } from './EventTimeline'
import { projectStages } from './runProjection'
import { StageRail } from './StageRail'
import { useRunEventStream } from './useRunEventStream'

type Tab = 'events' | 'result' | 'changes'

const terminalStatuses = new Set<RunExecutionStatus>([
  'COMPLETED', 'FAILED', 'CANCELLED', 'INFRASTRUCTURE_FAILED',
])

const statusLabels: Record<RunExecutionStatus, string> = {
  QUEUED: '等待执行',
  RUNNING: '正在执行',
  COMPLETED: '执行通过',
  FAILED: '执行失败',
  CANCELLED: '已取消',
  INFRASTRUCTURE_FAILED: '基础设施故障',
}

const streamLabels = {
  connecting: '正在连接事件流',
  open: 'SSE 实时连接',
  reconnecting: '事件流正在重连',
  completed: '事件流已完成',
  failed: '事件流数据异常',
}

const delay = (milliseconds: number) => new Promise((resolve) => setTimeout(resolve, milliseconds))

/** 展示一次 Run 的阶段进度、事件、结果和代码变更。 */
export function RunPage() {
  const { runId = '' } = useParams()
  const [tab, setTab] = useState<Tab>('events')
  const reconciledTerminal = useRef<number | null>(null)
  const statusQuery = useQuery({
    queryKey: ['run-status', runId],
    queryFn: () => getRunStatus(runId),
    enabled: Boolean(runId),
    staleTime: Infinity,
  })
  const stream = useRunEventStream(runId, statusQuery.isSuccess)
  const stages = useMemo(() => projectStages(stream.events), [stream.events])
  const terminalStatus = statusQuery.data
    ? terminalStatuses.has(statusQuery.data.status)
    : false
  const resultQuery = useQuery({
    queryKey: ['run-result', runId],
    queryFn: () => getRunResult(runId),
    enabled: terminalStatus && statusQuery.data?.status !== 'INFRASTRUCTURE_FAILED',
    retry: 2,
  })
  const changesQuery = useQuery({
    queryKey: ['run-changes', runId],
    queryFn: () => getRunChanges(runId),
    enabled: Boolean(resultQuery.data?.changesAvailable),
    retry: false,
  })

  useEffect(() => {
    const terminalSequence = stream.terminal?.sequence
    if (!terminalSequence || reconciledTerminal.current === terminalSequence) return
    reconciledTerminal.current = terminalSequence
    let cancelled = false

    const reconcile = async () => {
      for (let attempt = 0; attempt < 5 && !cancelled; attempt++) {
        const response = await statusQuery.refetch()
        if (response.data && terminalStatuses.has(response.data.status)) return
        await delay(200)
      }
    }
    void reconcile()
    return () => { cancelled = true }
  }, [stream.terminal?.sequence, statusQuery.refetch])

  useEffect(() => {
    if (terminalStatus) setTab('result')
  }, [terminalStatus])

  if (statusQuery.isLoading) {
    return <div className="page-message">正在读取 Run 状态…</div>
  }
  if (statusQuery.isError || !statusQuery.data) {
    return (
      <div className="page-message error-page">
        <strong>无法读取这个 Coding Run</strong>
        <p>{statusQuery.error?.message ?? '请确认 Control Plane 正在运行，并检查 Run ID。'}</p>
        <Link to="/"><ArrowLeft size={16} /> 返回任务创建</Link>
      </div>
    )
  }

  const status = statusQuery.data
  return (
    <div className="run-page">
      <div className="run-toolbar">
        <Link className="back-link" to="/"><ArrowLeft size={16} /> 新建 Run</Link>
        <div className="run-identity">
          <span>RUN</span>
          <code>{runId}</code>
        </div>
        <div className={`stream-badge stream-${stream.streamState}`}>
          {stream.streamState === 'reconnecting' ? <RotateCcw className="spin" size={14} /> : <Radio size={14} />}
          {streamLabels[stream.streamState]}
        </div>
      </div>

      <section className="run-overview">
        <div>
          <p className="eyebrow">CURRENT STATUS</p>
          <h1>{statusLabels[status.status]}</h1>
          <p>{status.message}</p>
        </div>
        <dl>
          <div><dt>Worktree</dt><dd title={status.worktree ?? ''}>{status.worktree ?? '等待创建'}</dd></div>
          <div><dt>变更文件</dt><dd>{status.changedFiles ?? '—'}</dd></div>
          <div><dt>Maven</dt><dd>{status.mavenStatus ?? '—'}</dd></div>
          <div><dt>事件游标</dt><dd>#{stream.lastSequence}</dd></div>
        </dl>
      </section>

      <section className="stage-section">
        <div className="section-heading">
          <span><CircleDot size={16} /> 五阶段主链路</span>
          <small>状态完全来自 Run 事实事件</small>
        </div>
        <StageRail stages={stages} />
      </section>

      <section className="run-detail">
        <nav className="detail-tabs" aria-label="Run 详情">
          <button className={tab === 'events' ? 'active' : ''} onClick={() => setTab('events')}>
            执行过程
          </button>
          <button className={tab === 'result' ? 'active' : ''} disabled={!terminalStatus} onClick={() => setTab('result')}>
            执行结果
          </button>
          <button className={tab === 'changes' ? 'active' : ''} disabled={!resultQuery.data?.changesAvailable} onClick={() => setTab('changes')}>
            代码变更 <span>{status.changedFiles ?? 0}</span>
          </button>
        </nav>

        <div className="detail-body">
          {tab === 'events' && <EventTimeline events={stream.events} />}
          {tab === 'result' && resultQuery.isLoading && <div className="empty-state">正在汇总最终结果…</div>}
          {tab === 'result' && resultQuery.data && <RunResultPanel result={resultQuery.data} />}
          {tab === 'result' && status.status === 'INFRASTRUCTURE_FAILED' && (
            <div className="empty-state">任务因基础设施故障结束，没有可用的业务结果。请查看事件和服务日志。</div>
          )}
          {tab === 'result' && resultQuery.isError && <div className="empty-state error-text">{resultQuery.error.message}</div>}
          {tab === 'changes' && changesQuery.isLoading && <div className="empty-state">正在读取 Unified Diff…</div>}
          {tab === 'changes' && changesQuery.data && <DiffPanel changes={changesQuery.data} />}
          {tab === 'changes' && changesQuery.isError && <div className="empty-state error-text">{changesQuery.error.message}</div>}
        </div>
      </section>
    </div>
  )
}
