import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ReviewDraftPanel } from './ReviewDraftPanel'

describe('ReviewDraftPanel', () => {
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
