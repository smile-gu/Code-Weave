import { Navigate, Route, Routes } from 'react-router-dom'
import { lazy, Suspense } from 'react'
import { CodingSessionListPage } from '../features/coding-session/CodingSessionListPage'
import { WorkspaceShell } from './WorkspaceShell'

// Keep the diff / Markdown workspace out of the initial home-page bundle.
const CodingSessionDetailPage = lazy(() => import('../features/coding-session/CodingSessionDetailPage').then(module => ({ default: module.CodingSessionDetailPage })))

/** 提供工作台公共外壳和页面路由。 */
export function App() {
  return (
    <WorkspaceShell>
        <Suspense fallback={<div className="page-message" role="status">正在打开工作区…</div>}><Routes>
          <Route path="/" element={<Navigate to="/sessions" replace />} />
          <Route path="/sessions" element={<CodingSessionListPage />} />
          <Route path="/sessions/:sessionId" element={<CodingSessionDetailPage />} />
          <Route path="*" element={<Navigate to="/sessions" replace />} />
        </Routes></Suspense>
    </WorkspaceShell>
  )
}
