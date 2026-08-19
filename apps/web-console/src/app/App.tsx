import { Activity, GitPullRequestArrow } from 'lucide-react'
import { Link, Navigate, Route, Routes } from 'react-router-dom'
import { CodingSessionDetailPage } from '../features/coding-session/CodingSessionDetailPage'
import { CodingSessionListPage } from '../features/coding-session/CodingSessionListPage'

/** 提供工作台公共外壳和页面路由。 */
export function App() {
  return (
    <div className="app-shell">
      <header className="topbar">
        <Link className="brand" to="/sessions">
          <span className="brand-mark"><GitPullRequestArrow size={19} /></span>
          <span>
            <strong>Code Weave</strong>
            <small>Coding Review 工作台</small>
          </span>
        </Link>
        <div className="topbar-actions">
          <nav className="workspace-nav" aria-label="工作台入口">
            <Link to="/sessions">持续 Coding</Link>
          </nav>
          <div className="local-badge">
            <Activity size={14} />
            本机控制平面
          </div>
        </div>
      </header>
      <main>
        <Routes>
          <Route path="/" element={<Navigate to="/sessions" replace />} />
          <Route path="/sessions" element={<CodingSessionListPage />} />
          <Route path="/sessions/:sessionId" element={<CodingSessionDetailPage />} />
          <Route path="*" element={<Navigate to="/sessions" replace />} />
        </Routes>
      </main>
    </div>
  )
}
