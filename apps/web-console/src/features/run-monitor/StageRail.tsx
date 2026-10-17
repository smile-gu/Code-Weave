import { Check, Circle, LoaderCircle, X } from 'lucide-react'
import type { StageView } from './runProjection'

const icons = {
  pending: <Circle size={16} />,
  running: <LoaderCircle className="spin" size={16} />,
  completed: <Check size={16} />,
  failed: <X size={16} />,
  skipped: <Circle size={16} />,
  stopped: <Circle size={16} />,
  incomplete: <Circle size={16} />,
}

type StageRailProps = {
  stages: StageView[]
  ariaLabel?: string
}

/** 以固定顺序展示五阶段工作流进度和各阶段最新摘要。 */
export function StageRail({ stages, ariaLabel = 'Coding Run 阶段进度' }: StageRailProps) {
  return (
    <ol className="stage-rail" aria-label={ariaLabel}>
      {stages.map((stage) => (
        <li className={`stage-item stage-${stage.status}`} key={stage.key}>
          <span className="stage-icon">{icons[stage.status]}</span>
          <span>
            <strong>{stage.label}</strong>
            <small title={stage.summary ?? stage.description}>{stage.summary ?? stage.description}</small>
          </span>
        </li>
      ))}
    </ol>
  )
}
