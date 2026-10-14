import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { ReviewHandoffPanel } from './ReviewHandoffPanel'

const content = { handoffId: 'h1', approvalRequestId: 'approval', contentDigest: 'sha256:handoff', reportRevision: 2, snapshotId: 'snapshot', reportInstructions: 'user requirements', approvalInstructions: '', materials: [{ review: { findingId: 'f1', title: 'Fix null', description: 'human description', fixInstructions: 'preserve behavior', originalDisposition: 'RISK', note: '' }, originalFindingJson: '{"assumptions":["verify caller"]}', evidenceJson: ['source'] }] }
const prepared = { content, lifecycle: { status: 'PREPARED', turnId: null } }
const approval = { requestId: 'approval', revision: 2, revisionDigest: 'sha256:revision', selectedFindingIds: ['f1'], incompleteAcknowledged: true, userInstructions: '', createdAt: 'now' }
function show(active = true) { render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><ReviewHandoffPanel sessionId="s" runId="r" sessionActive={active} approval={approval} /></QueryClientProvider>) }
const ok = (value: unknown) => ({ ok: true, json: async () => value })
afterEach(() => { vi.unstubAllGlobals(); sessionStorage.clear() })

it('prepares without starting and explicitly starts the displayed digest', async () => {
  let views: unknown[] = []
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === 'POST') {
      if (url.endsWith('/start')) return ok({ ...prepared, lifecycle: { status: 'STARTED', turnId: 't1' } })
      views = [prepared]; return ok(prepared)
    }
    return ok(views)
  })
  vi.stubGlobal('fetch', fetch); show()
  const prepare = await screen.findByRole('button', { name: '准备修复交接' })
  await waitFor(() => expect(prepare).toBeEnabled())
  fireEvent.click(prepare)
  await screen.findByText('human description')
  expect(fetch.mock.calls.filter(([url]) => url.endsWith('/start'))).toHaveLength(0)
  expect(screen.getByText(/原始等级：RISK/)).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '启动 Coding' }))
  await screen.findByText(/t1/)
  const sent = fetch.mock.calls.find(([url]) => url.endsWith('/start'))!
  expect(JSON.parse(String(sent[1]?.body)).expectedHandoffDigest).toBe('sha256:handoff')
})

it('restores an unknown start without sending and retries the exact identity', async () => {
  const intent = { action: 'start', handoffId: 'h1', body: { requestId: 'original-request', expectedHandoffDigest: 'sha256:handoff' } }
  sessionStorage.setItem('code-weave:handoff-intent:s:r', JSON.stringify(intent))
  const fetch = vi.fn(async (_url: string, init?: RequestInit) => init?.method === 'POST'
    ? ok({ ...prepared, lifecycle: { status: 'STARTED', turnId: 't1' } }) : ok([prepared]))
  vi.stubGlobal('fetch', fetch); show()
  await screen.findByText('human description')
  expect(fetch.mock.calls.every(([, init]) => !init?.method)).toBe(true)
  expect(screen.getByRole('button', { name: '启动 Coding' })).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: '重试原交接请求' }))
  await waitFor(() => expect(fetch.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(true))
  expect(JSON.parse(String(fetch.mock.calls.find(([, init]) => init?.method === 'POST')![1]?.body))).toEqual(intent.body)
})

it('keeps blocked and closed history read only', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => ok([{ ...prepared, lifecycle: { status: 'START_BLOCKED', reason: 'WORKSPACE_DRIFTED', turnId: null } }])))
  show(false)
  await screen.findByText('human description')
  expect(screen.getByRole('button', { name: '启动 Coding' })).toBeDisabled()
  expect(screen.getByText(/重新审查/)).toBeInTheDocument()
})

it('can explicitly reconcile persisted STARTING after browser retry storage is gone', async () => {
  const fetch = vi.fn(async (_url: string, init?: RequestInit) => init?.method === 'POST'
    ? ok({ ...prepared, lifecycle: { status: 'STARTED', turnId: 't1' } })
    : ok([{ ...prepared, lifecycle: { status: 'STARTING', turnId: 'fixed-turn', startRequestId: 'persisted-request' } }]))
  vi.stubGlobal('fetch', fetch); show()
  const retry = await screen.findByRole('button', { name: '核实并重试原启动' })
  expect(fetch.mock.calls.every(([, init]) => !init?.method)).toBe(true)
  fireEvent.click(retry)
  await screen.findByText(/t1/)
  expect(JSON.parse(String(fetch.mock.calls.find(([, init]) => init?.method === 'POST')![1]?.body)))
    .toEqual({ requestId: 'persisted-request', expectedHandoffDigest: 'sha256:handoff' })
})
