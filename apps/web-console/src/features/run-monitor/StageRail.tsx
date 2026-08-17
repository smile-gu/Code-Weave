import { Check, Circle, LoaderCircle, X } from 'lucide-react'
import type { StageView } from './runProjection'

const icons = {
  pending: <Circle size={16} />,
  running: <LoaderCircle className="spin" size={16} />,
  completed: <Check size={16} />,
  failed: <X size={16} />,
}

/** 以固定顺序展示五阶段工作流进度。 */
export function StageRail({ stages }: { stages: StageView[] }) {
  return (
    <ol className="stage-rail" aria-label="Coding Run 阶段进度">
      {stages.map((stage) => (
        <li className={`stage-item stage-${stage.status}`} key={stage.key}>
          <span className="stage-icon">{icons[stage.status]}</span>
          <span>
            <strong>{stage.label}</strong>
            <small>{stage.description}</small>
          </span>
        </li>
      ))}
    </ol>
  )
}
