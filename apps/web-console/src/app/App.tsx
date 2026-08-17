import { Activity, GitPullRequestArrow } from 'lucide-react'
import { Link, Navigate, Route, Routes } from 'react-router-dom'
import { RunCreatePage } from '../features/run-create/RunCreatePage'
import { RunPage } from '../features/run-monitor/RunPage'

/** 提供工作台公共外壳和页面路由。 */
export function App() {
  return (
    <div className="app-shell">
      <header className="topbar">
        <Link className="brand" to="/">
          <span className="brand-mark"><GitPullRequestArrow size={19} /></span>
          <span>
            <strong>Code Weave</strong>
            <small>Coding Run 工作台</small>
          </span>
        </Link>
        <div className="local-badge">
          <Activity size={14} />
          本机控制平面
        </div>
      </header>
      <main>
        <Routes>
          <Route path="/" element={<RunCreatePage />} />
          <Route path="/runs/:runId" element={<RunPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </div>
  )
}
