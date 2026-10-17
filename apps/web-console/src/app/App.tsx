import { Navigate, Route, Routes } from 'react-router-dom'
import { CodingSessionDetailPage } from '../features/coding-session/CodingSessionDetailPage'
import { CodingSessionListPage } from '../features/coding-session/CodingSessionListPage'
import { WorkspaceShell } from './WorkspaceShell'

/** 提供工作台公共外壳和页面路由。 */
export function App() {
  return (
    <WorkspaceShell>
        <Routes>
          <Route path="/" element={<Navigate to="/sessions" replace />} />
          <Route path="/sessions" element={<CodingSessionListPage />} />
          <Route path="/sessions/:sessionId" element={<CodingSessionDetailPage />} />
          <Route path="*" element={<Navigate to="/sessions" replace />} />
        </Routes>
    </WorkspaceShell>
  )
}
