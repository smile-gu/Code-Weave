import {
  Check, CircleAlert, CircleX, LoaderCircle, RefreshCw,
} from 'lucide-react'
import type {
  RuntimeCheckStatus, RuntimeComponent, RuntimeReadiness,
} from '../../../shared/api/types'

const componentLabels: Record<RuntimeComponent, string> = {
  RUNTIME_ROOT: '运行目录',
  GIT: 'Git',
  JAVA: 'Java',
  MAVEN: 'Maven',
  CLAUDE_CODE: 'Claude Code',
}

const statusIcons: Record<RuntimeCheckStatus, React.ReactNode> = {
  PASSED: <Check size={13} />,
  WARNING: <CircleAlert size={13} />,
  FAILED: <CircleX size={13} />,
}

type RuntimeReadinessPanelProps = {
  readiness: RuntimeReadiness | undefined
  loading: boolean
  refreshing: boolean
  error: string | null
  onRefresh: () => void
}

/** 展示本机运行依赖状态和失败项的处理建议。 */
export function RuntimeReadinessPanel({
  readiness,
  loading,
  refreshing,
  error,
  onRefresh,
}: RuntimeReadinessPanelProps) {
  const ready = readiness?.status === 'READY'
  return (
    <section className="readiness-panel" aria-live="polite">
      <div className="readiness-heading">
        <div>
          <span className={`readiness-verdict ${ready ? 'readiness-ready' : 'readiness-blocked'}`}>
            {loading
              ? <><LoaderCircle className="spin" size={13} /> 正在检查运行环境</>
              : ready
                ? <><Check size={13} /> 运行环境已就绪</>
                : <><CircleAlert size={13} /> 运行环境未就绪</>}
          </span>
        </div>
        <button className="readiness-refresh" disabled={loading || refreshing} onClick={onRefresh} type="button">
          <RefreshCw className={refreshing ? 'spin' : ''} size={13} />
          重新检查
        </button>
      </div>

      {error && (
        <div className="readiness-connection-error" role="alert">
          <strong>无法连接本机 Control Plane</strong>
          <span>{error}</span>
        </div>
      )}

      {readiness && (
        <ul className="readiness-checks">
          {readiness.checks.map((check) => (
            <li className={`readiness-check check-${check.status.toLowerCase()}`} key={check.component}>
              <span className="check-icon">{statusIcons[check.status]}</span>
              <div>
                <div className="check-title">
                  <strong>{componentLabels[check.component]}</strong>
                  {check.version && <code>{check.version}</code>}
                </div>
                <p>{check.message}</p>
                {check.remediation && <small>{check.remediation}</small>}
                {check.executable && <code className="check-path" title={check.executable}>{check.executable}</code>}
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
