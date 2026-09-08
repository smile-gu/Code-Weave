import { useEffect, useMemo, useRef, useState } from 'react'
import { ChevronDown, CircleDot, Radio } from 'lucide-react'
import type { CodingTurn, RunEvent } from '../../shared/api/types'
import { ActivityTimeline } from '../run-monitor/ActivityTimeline'
import { projectActivities } from '../run-monitor/activityProjection'
import { StageRail } from '../run-monitor/StageRail'
import { projectTurnStages } from './turnProjection'
import { getCodingTurn } from '../../shared/api/sessionApi'
import {
  confirmTerminalTurn,
  isTerminalTurn,
  TURN_STATUS_RECONCILIATION_MS,
} from './turnStatusConfirmation'

type TurnExecutionPanelProps = {
  sessionId: string
  turn: CodingTurn
  onTerminal: () => void
}

const terminalTypes = new Set(['RUN_COMPLETED', 'RUN_FAILED', 'RUN_CANCELLED'])

/** 在对话中展示一轮 Turn 的横向阶段，并按需加载上层执行活动。 */
export function TurnExecutionPanel({ sessionId, turn, onTerminal }: TurnExecutionPanelProps) {
  const active = turn.status === 'QUEUED' || turn.status === 'RUNNING'
  const [expanded, setExpanded] = useState(false)
  const [events, setEvents] = useState<RunEvent[]>([])
  const [streamConnected, setStreamConnected] = useState(false)
  const terminalObserved = useRef(false)
  const terminalConfirmed = useRef(false)
  const generation = useRef(0)
  const retryConfirmation = useRef<() => void>(() => {})
  const [confirmation, setConfirmation] = useState<'idle' | 'confirming' | 'unconfirmed' | 'network-error'>('idle')
  const stages = useMemo(() => projectTurnStages(events, turn), [events, turn])
  const activities = useMemo(() => projectActivities(events), [events])

  useEffect(() => {
    terminalObserved.current = false
    terminalConfirmed.current = !active
    setConfirmation('idle')
    if (!active && !expanded) return
    const currentGeneration = ++generation.current
    const isCurrent = () => generation.current === currentGeneration
    const canConfirm = () => isCurrent() && !terminalConfirmed.current
    let confirming = false
    let polling = false
    let reconciliation: number | undefined
    const controller = new AbortController()
    /** 页面离开或终态确认后立即解除退避定时器。 */
    const wait = (milliseconds: number) => new Promise<void>((resolve) => {
      const stop = () => { window.clearTimeout(timer); controller.signal.removeEventListener('abort', stop); resolve() }
      const timer = window.setTimeout(stop, milliseconds)
      controller.signal.addEventListener('abort', stop, { once: true })
      if (controller.signal.aborted) stop()
    })
    const source = new EventSource(
      `/api/v1/coding-sessions/${sessionId}/turns/${turn.turnId}/stream`,
    )
    source.onopen = () => { if (isCurrent()) setStreamConnected(true) }
    source.onerror = () => {
      if (canConfirm() && active) { setStreamConnected(false); setConfirmation('network-error') }
    }

    /** 所有确认入口共同关闭实时资源并清除尚未确认提示。 */
    const finishConfirmation = () => {
      if (!canConfirm()) return
      terminalConfirmed.current = true
      terminalObserved.current = true
      controller.abort()
      setConfirmation('idle')
      source.close()
      if (reconciliation !== undefined) window.clearInterval(reconciliation)
      setStreamConnected(false)
      onTerminal()
    }

    /** 接收可用于产品展示的事实事件，并过滤大段控制台输出。 */
    const receive = (raw: Event) => {
      if (!isCurrent()) return
      try {
        const event = JSON.parse((raw as MessageEvent<string>).data) as RunEvent
        if (event.type !== 'PROCESS_STDOUT' && event.type !== 'PROCESS_STDERR'
          && agentEventType(event) !== 'USAGE_UPDATED') {
          setEvents((current) => current.some((item) => item.eventId === event.eventId)
            ? current
            : [...current, event])
        }
        if (active && terminalTypes.has(event.type) && !terminalObserved.current) {
          terminalObserved.current = true
          void confirmTerminal()
        }
      } catch {
        // 非结构化事件不进入展示投影，事实日志仍由 Control Plane 保留。
      }
    }

    /** 使用持久化单 Turn 查询确认 SSE 所通知的终态。 */
    const confirmTerminal = async () => {
      if (!canConfirm() || confirming) return
      confirming = true
      if (terminalObserved.current) setConfirmation('confirming')
      const result = await confirmTerminalTurn(
        () => getCodingTurn(sessionId, turn.turnId, controller.signal), canConfirm, wait,
      )
      confirming = false
      if (!canConfirm() || result.kind === 'stale') return
      if (result.kind === 'confirmed') {
        finishConfirmation()
        return
      }
      setConfirmation(result.error ? 'network-error' : 'unconfirmed')
    }
    retryConfirmation.current = () => { void confirmTerminal() }

    reconciliation = active ? window.setInterval(() => {
      if (!canConfirm() || polling) return
      polling = true
      void getCodingTurn(sessionId, turn.turnId, controller.signal).then((persisted) => {
        if (!canConfirm()) return
        if (isTerminalTurn(persisted)) finishConfirmation()
        else if (!terminalObserved.current) setConfirmation('idle')
      }).catch(() => {
        if (canConfirm()) setConfirmation('network-error')
      }).finally(() => { polling = false })
    }, TURN_STATUS_RECONCILIATION_MS) : undefined

    source.addEventListener('run-event', receive)
    return () => {
      generation.current++
      controller.abort()
      retryConfirmation.current = () => {}
      if (reconciliation !== undefined) window.clearInterval(reconciliation)
      source.removeEventListener('run-event', receive)
      source.close()
      setStreamConnected(false)
    }
  }, [active, expanded, onTerminal, sessionId, turn.turnId])

  return (
    <section className="turn-execution-panel">
      <div className="turn-execution-heading">
        <span><CircleDot size={15} /> 第 {turn.sequence} 轮执行链路</span>
        <small className={active ? 'turn-stream-active' : ''}>
          {active && streamConnected ? <><Radio size={12} /> SSE 实时更新</> : statusLabel(turn)}
        </small>
      </div>
      <StageRail stages={stages} ariaLabel={`第 ${turn.sequence} 轮 Coding 阶段进度`} />
      {confirmation !== 'idle' && (
        <div className="turn-confirmation-notice" role="status">
          <span>{confirmation === 'confirming'
            ? '正在确认最终状态…'
            : confirmation === 'network-error'
              ? '连接异常，尚未确认最终状态。'
              : '尚未确认最终状态，当前任务仍保持占用。'}</span>
          {confirmation !== 'confirming' && (
            <button type="button" onClick={() => retryConfirmation.current()}>手动重试</button>
          )}
        </div>
      )}
      <details className="turn-activity-details" open={expanded}
        onToggle={(event) => setExpanded(event.currentTarget.open)}>
        <summary>
          <span>查看执行活动</span>
          <small>{expanded ? `${activities.length} 项上层活动` : '已按用户视角聚合技术事件'}</small>
          <ChevronDown size={15} />
        </summary>
        <div className="turn-activity-body">
          {events.length === 0 && !active
            ? <div className="empty-state">正在读取本轮历史活动…</div>
            : <ActivityTimeline activities={activities} />}
        </div>
      </details>
    </section>
  )
}

/** 读取兼容单轮和多轮协议的 Agent 事件类型。 */
function agentEventType(event: RunEvent): unknown {
  return event.payload.agentEventType ?? event.payload.eventType
}

/** 返回执行面板右上角的简短终态。 */
function statusLabel(turn: CodingTurn) {
  if (turn.status === 'QUEUED') return '等待执行'
  if (turn.status === 'RUNNING') return '正在执行'
  if (turn.status === 'CANCELLED') return '本轮已停止'
  if (turn.status === 'INTERRUPTED') return '执行被中断'
  if (turn.outcome === 'CHANGES_VERIFIED') return '代码修改已验证'
  if (turn.outcome === 'NO_CHANGES') return '本轮无代码修改'
  if (turn.outcome === 'POLICY_VIOLATION') return '修改超出路径边界'
  if (turn.outcome === 'VERIFICATION_FAILED') return 'Maven 验证未通过'
  if (turn.outcome === 'WORKSPACE_DRIFT') return '工作区分支或 HEAD 已变化'
  return turn.status === 'FAILED' ? '执行失败' : '执行完成'
}
