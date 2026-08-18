import {
  Bot, Check, CircleAlert, CircleX, CodeXml, FileCheck2, FolderGit2, LoaderCircle,
  ShieldCheck,
} from 'lucide-react'
import type { ActivityCategory, ActivityItem, ActivityStatus } from './activityProjection'

const time = new Intl.DateTimeFormat('zh-CN', {
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
})

const statusIcons: Record<ActivityStatus, React.ReactNode> = {
  running: <LoaderCircle className="spin" size={14} />,
  success: <Check size={14} />,
  warning: <CircleAlert size={14} />,
  failed: <CircleX size={14} />,
}

const categoryIcons: Record<ActivityCategory, React.ReactNode> = {
  run: <CodeXml size={14} />,
  workspace: <FolderGit2 size={14} />,
  agent: <Bot size={14} />,
  change: <ShieldCheck size={14} />,
  verification: <FileCheck2 size={14} />,
}

/** 展示由原始事件聚合出的用户活动时间线。 */
export function ActivityTimeline({ activities }: { activities: ActivityItem[] }) {
  if (activities.length === 0) {
    return <div className="empty-state">等待第一项执行活动…</div>
  }

  const activityIds = new Set(activities.map((activity) => activity.id))
  const childrenByParent = new Map<string, ActivityItem[]>()
  const roots: ActivityItem[] = []

  for (const activity of activities) {
    if (!activity.parentId || !activityIds.has(activity.parentId)) {
      roots.push(activity)
      continue
    }
    const children = childrenByParent.get(activity.parentId) ?? []
    children.push(activity)
    childrenByParent.set(activity.parentId, children)
  }

  /** 递归渲染活动及其子活动，保留 Agent 内部操作的层级关系。 */
  const renderActivity = (activity: ActivityItem): React.ReactNode => {
    const children = childrenByParent.get(activity.id) ?? []
    return (
      <li className={`activity-item activity-${activity.status}`} key={activity.id}>
        <div className="activity-row">
          <span className="activity-category-icon">{categoryIcons[activity.category]}</span>
          <span className="activity-status-icon">{statusIcons[activity.status]}</span>
          <div className="activity-content">
            <div className="activity-title">
              <strong>{activity.title}</strong>
              <time>{time.format(new Date(activity.timestamp))}</time>
            </div>
            <p>{activity.summary}</p>
            <details className="activity-details">
              <summary>技术详情</summary>
              {activity.details.length > 0 && (
                <dl>
                  {activity.details.map((detail) => (
                    <div key={detail.label}>
                      <dt>{detail.label}</dt>
                      <dd>{detail.value}</dd>
                    </div>
                  ))}
                </dl>
              )}
              <div className="activity-sources">
                <span>关联事件</span>
                <code>{activity.sourceSequences.map((sequence) => `#${sequence}`).join('、')}</code>
              </div>
            </details>
          </div>
        </div>
        {children.length > 0 && (
          <ol className="activity-children" aria-label={`${activity.title}的执行明细`}>
            {children.map(renderActivity)}
          </ol>
        )}
      </li>
    )
  }

  return (
    <ol className="activity-list">
      {roots.map(renderActivity)}
    </ol>
  )
}
