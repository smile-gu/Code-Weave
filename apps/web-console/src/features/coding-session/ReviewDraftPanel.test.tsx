import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
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
    const button = await screen.findByRole('button', { name: /multi-run/ })
    expect(await screen.findByText(/FEE_BUDGET_EXHAUSTED/)).toBeInTheDocument()
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
    const button = await screen.findByRole('button', { name: /run-fixed/ })
    button.click()
    expect(await screen.findByText('INCOMPLETE / PARTIAL')).toBeInTheDocument()
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
})
