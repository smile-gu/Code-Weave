import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createCodingSession, getRecentCodingSessions } from '../../shared/api/sessionApi'
import { ApiError } from '../../shared/api/http'
import { CodingSessionListPage } from './CodingSessionListPage'

vi.mock('../../shared/api/sessionApi', () => ({ createCodingSession: vi.fn(), getRecentCodingSessions: vi.fn() }))

describe('CodingSessionListPage form feedback', () => {
  beforeEach(() => { vi.clearAllMocks(); vi.mocked(getRecentCodingSessions).mockResolvedValue({ sessions: [] }) })
  it('associates inline API errors with the field and preserves the input for correction', async () => {
    vi.mocked(createCodingSession).mockRejectedValue(new ApiError({ timestamp: '2026-10-17T00:00:00Z', path: '/api/v1/coding-sessions', runId: null, status: 400, code: 'VALIDATION_ERROR', message: '请检查仓库路径', fieldErrors: { repository: '仓库路径无效' } }))
    render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><MemoryRouter><CodingSessionListPage /></MemoryRouter></QueryClientProvider>)
    const input = screen.getByRole('textbox', { name: /本地 Git 仓库/ })
    fireEvent.change(input, { target: { value: 'D:/invalid-repo' } })
    fireEvent.click(screen.getByRole('button', { name: '创建 Coding Session' }))
    await screen.findByText('仓库路径无效')
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input).toHaveAccessibleDescription('仓库路径无效')
    expect(input).toHaveValue('D:/invalid-repo')
    expect(createCodingSession).toHaveBeenCalledTimes(1)
  })
})
