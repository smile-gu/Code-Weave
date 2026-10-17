import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ReviewDraftPanel } from './ReviewDraftPanel'

describe('ReviewDraftPanel', () => {
  it('shows selected multipass coverage and durable skip reasons without calling it six-pass completion', async () => {
    const fetch = vi.fn().mockImplementation(async (url: string) => ({ ok: true, json: async () => url.endsWith('/result') ? {
      resultVersion: '3.0', snapshotId: 'fixed', coverageStatus: 'SELECTED_INCOMPLETE', reviewPlan: { selectedPasses: ['correctness', 'testing'] },
      passResults: [{ passId: 'correctness', selected: true, status: 'INCOMPLETE', coverage: 'PARTIAL', checkedScope: [], limitations: ['No material'], incompleteReasons: ['budget'], skipReason: null },
        { passId: 'security', selected: false, status: 'SKIPPED', coverage: 'PARTIAL', checkedScope: [], limitations: [], incompleteReasons: [], skipReason: 'USER_NOT_SELECTED' },
        { passId: 'testing', selected: true, status: 'SKIPPED', coverage: 'PARTIAL', checkedScope: [], limitations: [], incompleteReasons: [], skipReason: 'RUN_BUDGET_EXHAUSTED' }],
      findings: [], evidence: [], reportDraft: { markdown: '# 固定草稿' },
    } : [{ runId: 'multi-run', status: 'COMPLETED', simulationOnly: false, snapshotId: 'fixed', passProgress: [{ passId: 'correctness', status: 'INCOMPLETE', reason: 'FEE_BUDGET_EXHAUSTED' }] }] }))
    vi.stubGlobal('fetch', fetch)
    render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><ReviewDraftPanel sessionId="multi-session" /></QueryClientProvider>)
    const button = await screen.findByRole('button', { name: /审查 1/ })
    expect(button).not.toHaveTextContent('multi-run')
    expect(await screen.findByText(/费用预算已耗尽/)).toHaveAttribute('title', 'FEE_BUDGET_EXHAUSTED')
    button.click()
    expect(await screen.findByText(/所选维度尚未完成/)).toBeInTheDocument()
    expect(screen.getByText(/用户未选择/)).toBeInTheDocument()
    expect(screen.getByText(/总预算耗尽，尚未执行/)).toBeInTheDocument()
    expect(screen.queryByText('六维审查完成')).toBeNull()
    expect(fetch.mock.calls.every(([url]) => String(url).startsWith('/api/v1/'))).toBe(true)
    vi.unstubAllGlobals()
  })
  it('reads a historical report through Java only and renders a bounded draft safely', async () => {
    const fetch = vi.fn().mockImplementation(async (url: string) => ({ ok: true, json: async () =>
      url.endsWith('/materials') ? { materials: [{ text: '<img src=x onerror=alert(1)>\npassword = [REDACTED]', coverage: 'PARTIAL', redactionState: 'APPLIED' }] } : url.endsWith('/result') ? {
        resultVersion: '2.0', snapshotId: 'snapshot-fixed',
        passResults: [{ status: 'INCOMPLETE', coverage: 'PARTIAL', checkedScope: ['a.java'], limitations: ['finite scope'], incompleteReasons: ['budget'] }],
        findings: [], evidence: [{ evidenceId: 'evidence-fixed', evidenceType: 'CODE', summary: 'fixed source', coverage: 'PARTIAL', sourceRefs: [{ sourceType: 'CODE', path: 'a.java', side: 'final', startLine: 1, endLine: 2 }] }], reportDraft: { markdown: '# 草稿\n\n<script>alert(1)</script>\n\n[x](javascript:alert(1))' },
      } : [{ runId: 'run-fixed', status: 'COMPLETED', simulationOnly: false, snapshotId: 'snapshot-fixed' }],
    }))
    vi.stubGlobal('fetch', fetch)
    render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <ReviewDraftPanel sessionId="session" />
    </QueryClientProvider>)
    screen.getByText('Review 报告草稿')
    const button = await screen.findByRole('button', { name: /审查 1/ })
    expect(button).not.toHaveTextContent('run-fixed')
    button.click()
    expect(await screen.findByText('未完成 / 材料部分覆盖')).toHaveAttribute('title', 'INCOMPLETE / PARTIAL')
    expect(screen.getByText('finite scope')).toBeInTheDocument()
    expect(document.querySelector('script')).toBeNull()
    expect(document.querySelector('a[href^="javascript:"]')).toBeNull()
    screen.getByText('来源材料与覆盖').click()
    screen.getByRole('button', { name: '查看固定材料' }).click()
    expect(await screen.findByText(/password = \[REDACTED\]/)).toBeInTheDocument()
    expect(document.querySelector('img')).toBeNull()
    expect(fetch.mock.calls.map(([url]) => url)).toEqual([
      '/api/v1/coding-sessions/session/review-runs?limit=50',
      '/api/v1/coding-sessions/session/review-runs/run-fixed/result',
      '/api/v1/coding-sessions/session/review-runs/run-fixed/evidence/evidence-fixed/materials',
    ])
    vi.unstubAllGlobals()
  })
  it('keeps internal identities out of default reading flow while retaining provenance on demand', async () => {
    const fetch = vi.fn(async (url: string) => ({ ok: true, json: async () => url.endsWith('/result') ? {
      resultVersion: '3.0', snapshotId: 'snapshot-sensitive', coverageStatus: 'SELECTED_COMPLETE', reviewPlan: { selectedPasses: ['correctness'] },
      passResults: [], evidence: [], reportDraft: { markdown: '' }, findings: [
        { findingId: 'f1', title: '空值访问', claim: '空值会抛异常', severity: 'HIGH', disposition: 'VERIFIED', confidence: .9, evidenceIds: [], codeReferences: [{ path: 'src/Order.java', side: 'final', startLine: 12, endLine: 13 }] },
        { findingId: 'f2', title: '乘法溢出', claim: '总额超出整数范围', severity: 'MEDIUM', disposition: 'RISK', confidence: .7, evidenceIds: [], codeReferences: [] },
      ],
    } : [{ runId: 'run-sensitive', status: 'COMPLETED', simulationOnly: false }] }))
    vi.stubGlobal('fetch', fetch)
    render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><ReviewDraftPanel sessionId="s" /></QueryClientProvider>)
    await screen.findByText('空值访问')
    expect(screen.getByRole('button', { name: /审查 1/ })).not.toHaveAccessibleName(/run-sensitive/)
    const snapshot = screen.getByText('snapshot-sensitive')
    const details = snapshot.closest('details')!
    expect(details).not.toHaveAttribute('open')
    fireEvent.click(details.querySelector('summary')!)
    expect(details).toHaveAttribute('open')
    fireEvent.change(screen.getByRole('combobox', { name: '筛选严重程度' }), { target: { value: 'HIGH' } })
    expect(screen.getByText('空值访问')).toBeInTheDocument()
    expect(screen.queryByText('乘法溢出')).toBeNull()
    fireEvent.click(screen.getByText('空值访问'))
    expect(screen.getByText('src/Order.java')).toBeInTheDocument()
    fireEvent.change(screen.getByRole('textbox', { name: '搜索审查问题' }), { target: { value: '不存在' } })
    expect(screen.getByText('没有匹配的问题')).toBeInTheDocument()
    fireEvent.change(screen.getByRole('textbox', { name: '搜索审查问题' }), { target: { value: '' } })
    fireEvent.change(screen.getByRole('combobox', { name: '筛选严重程度' }), { target: { value: 'ALL' } })
    expect(screen.getByText('乘法溢出')).toBeInTheDocument()
    expect(fetch.mock.calls).toHaveLength(2)
    vi.unstubAllGlobals()
  })
  it('pins the selected report across history refreshes instead of replacing an open report', async () => {
    let refreshed = false
    const fetch = vi.fn(async (url: string) => ({ ok: true, json: async () => url.endsWith('/result')
      ? { resultVersion: '1.0', snapshotId: 'fixed', findings: [], evidence: [] }
      : [...(refreshed ? [{ runId: 'new-run', status: 'COMPLETED' }] : []), { runId: 'old-run', status: 'COMPLETED' }] }))
    vi.stubGlobal('fetch', fetch)
    render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><ReviewDraftPanel sessionId="s" /></QueryClientProvider>)
    await screen.findByText('模拟执行结果，不代表代码审查通过。')
    refreshed = true
    fireEvent.click(screen.getByRole('button', { name: '刷新审查历史' }))
    await waitFor(() => expect(screen.getByRole('button', { name: /审查 2/ })).toHaveAttribute('aria-pressed', 'false'))
    expect(screen.getByRole('button', { name: /审查 1/ })).toHaveAttribute('aria-pressed', 'true')
    expect(fetch.mock.calls.some(([url]) => url.includes('new-run/result'))).toBe(false)
    vi.unstubAllGlobals()
  })
})
