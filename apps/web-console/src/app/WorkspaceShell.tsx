import { useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, NavLink, useLocation } from 'react-router-dom'
import { ArrowUpRight, GitBranch, Layers2, MessageSquare, PanelLeftClose, PanelLeftOpen, Plus, Search, ShieldCheck } from 'lucide-react'
import { getRecentCodingSessions } from '../shared/api/sessionApi'
import { sessionStatusLabels } from '../features/coding-session/sessionPresentation'

/** Presentation only: opening navigation never starts a Run, Turn or Handoff. */
export function WorkspaceShell({ children }: { children: ReactNode }) {
  const [collapsed, setCollapsed] = useState(false)
  const [search, setSearch] = useState('')
  const location = useLocation()
  const sessions = useQuery({ queryKey: ['coding-sessions'], queryFn: () => getRecentCodingSessions(20), retry: false })
  const visible = sessions.data?.sessions.filter(session => `${session.title} ${session.sourceRepository}`.toLocaleLowerCase().includes(search.toLocaleLowerCase())) ?? []

  return <div className={`app-shell weave-workspace${collapsed ? ' sidebar-collapsed' : ''}`}>
    <a className="skip-link" href="#workspace-main">跳到工作区</a>
    <aside className="workspace-sidebar" aria-label="会话导航">
      <div className="sidebar-brand-row">
        <Link className="brand" to="/sessions" aria-label="Code Weave 首页"><span className="brand-mark"><Layers2 size={21} /></span><span className="sidebar-wide"><strong>Code Weave</strong><small>CODING × REVIEW</small></span></Link>
        <button className="icon-button sidebar-toggle" type="button" aria-label={collapsed ? '展开侧栏' : '收起侧栏'} aria-expanded={!collapsed} onClick={() => setCollapsed(value => !value)}>{collapsed ? <PanelLeftOpen size={17} /> : <PanelLeftClose size={17} />}</button>
      </div>
      <Link className="new-session-link" to="/sessions#new-session" title="新建会话"><Plus size={18} /><span className="sidebar-wide">新建会话</span><ArrowUpRight className="sidebar-wide" size={14} /></Link>
      <div className="sidebar-wide sidebar-search"><Search size={15} /><input aria-label="搜索会话" placeholder="搜索会话或仓库" value={search} onChange={event => setSearch(event.target.value)} /></div>
      <div className="sidebar-section-title sidebar-wide">工作区会话 <span>{sessions.data?.sessions.length ?? '—'}</span></div>
      <nav className="sidebar-sessions" aria-label="最近会话">
        {sessions.isPending && <p className="sidebar-wide sidebar-message">正在读取会话…</p>}
        {sessions.isError && <div className="sidebar-wide sidebar-message" role="status">会话读取失败<button type="button" onClick={() => void sessions.refetch()}>重试</button></div>}
        {!sessions.isPending && !sessions.isError && visible.length === 0 && <p className="sidebar-wide sidebar-message">{search ? '没有匹配的会话' : '还没有会话，从新建开始。'}</p>}
        {visible.map(session => <NavLink className={({ isActive }) => `sidebar-session${isActive ? ' is-active' : ''}`} key={session.sessionId} to={`/sessions/${session.sessionId}`} title={`${session.title} · ${sessionStatusLabels[session.status]}`}>
          <MessageSquare size={16} /><span className="sidebar-wide"><strong>{session.title}</strong><small>{session.sourceRepository.split(/[\\/]/).filter(Boolean).at(-1) ?? session.baseRef}</small></span><span className={`sidebar-state sidebar-wide status-${session.status.toLowerCase()}`} aria-label={sessionStatusLabels[session.status]} />
        </NavLink>)}
      </nav>
      <div className="sidebar-footer"><ShieldCheck size={17} /><div className="sidebar-wide"><strong>独立审查，明确交接</strong><small>报告批准不会自动启动 Coding</small></div></div>
    </aside>
    <div className="workspace-main-column">
      <header className="workspace-topbar"><div><GitBranch size={15} /><span>本地工作区</span><span className="topbar-divider">/</span><strong>{location.pathname === '/sessions' ? '开始协作' : '持续会话'}</strong></div><span className="workspace-local-indicator"><i /> 本地模式<span className="trial-label">受控试用</span></span></header>
      <main id="workspace-main" tabIndex={-1}>{children}</main>
    </div>
  </div>
}
