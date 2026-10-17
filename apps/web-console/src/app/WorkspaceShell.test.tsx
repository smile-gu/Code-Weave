import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getRecentCodingSessions } from '../shared/api/sessionApi'
import type { CodingSession } from '../shared/api/types'
import { WorkspaceShell } from './WorkspaceShell'

vi.mock('../shared/api/sessionApi', () => ({ getRecentCodingSessions: vi.fn() }))
const session = { sessionId: 'session-one', title: '订单模块', sourceRepository: 'D:/projects/orders', status: 'ACTIVE', baseRef: 'main' } as CodingSession
function show() {
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><MemoryRouter initialEntries={['/sessions/session-one']}><WorkspaceShell><p>当前工作区内容</p></WorkspaceShell></MemoryRouter></QueryClientProvider>)
}
describe('WorkspaceShell', () => {
  beforeEach(() => { vi.clearAllMocks(); vi.mocked(getRecentCodingSessions).mockResolvedValue({ sessions: [session] }) })
  it('reads real sessions, navigates without mutation and preserves content while collapsing', async () => {
    show()
    const link = await screen.findByRole('link', { name: /订单模块/ })
    expect(link).toHaveAttribute('href', '/sessions/session-one')
    expect(link).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: /新建会话/ })).toHaveAttribute('href', '/sessions#new-session')
    fireEvent.click(screen.getByRole('button', { name: '收起侧栏' }))
    expect(screen.getByRole('button', { name: '展开侧栏' })).toHaveAttribute('aria-expanded', 'false')
    expect(screen.getByText('当前工作区内容')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '展开侧栏' }))
    expect(screen.getByRole('button', { name: '收起侧栏' })).toHaveAttribute('aria-expanded', 'true')
    expect(getRecentCodingSessions).toHaveBeenCalledWith(20)
  })
  it('filters history by repository or title without changing server state', async () => {
    show()
    await screen.findByRole('link', { name: /订单模块/ })
    fireEvent.change(screen.getByRole('textbox', { name: '搜索会话' }), { target: { value: 'billing' } })
    expect(screen.getByText('没有匹配的会话')).toBeInTheDocument()
    fireEvent.change(screen.getByRole('textbox', { name: '搜索会话' }), { target: { value: 'orders' } })
    expect(screen.getByRole('link', { name: /订单模块/ })).toBeInTheDocument()
    expect(getRecentCodingSessions).toHaveBeenCalledTimes(1)
  })
  it('keeps query errors visible and retries only on explicit request', async () => {
    vi.mocked(getRecentCodingSessions).mockRejectedValueOnce(new Error('offline')).mockResolvedValue({ sessions: [] })
    show()
    expect(await screen.findByRole('status')).toHaveTextContent('会话读取失败')
    fireEvent.click(screen.getByRole('button', { name: '重试' }))
    expect(await screen.findByText('还没有会话，从新建开始。')).toBeInTheDocument()
  })
})
