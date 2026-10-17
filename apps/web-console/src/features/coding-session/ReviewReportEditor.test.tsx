import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ReviewReportEditor } from './ReviewReportEditor'
const revision = { reportVersion: '1.0', reportId: 'report', revision: 1, sourceResultId: 'result', sourceResultSha256: 'sha256:source',
  sourceResultVersion: '3.0', snapshotId: 'snapshot', manifestSha256: 'sha256:manifest', createdAt: 'now', revisionDigest: 'sha256:first', userInstructions: '',
  findingReviews: [{ findingId: 'f1', originalDisposition: 'RISK', title: 'Original title', description: 'Assumption remains', fixInstructions: 'Check assumption', note: '', decision: 'PENDING' }] }
const view = { reportRevision: revision, currentRevision: 1, editable: true, interaction: 'OPEN', revisionHistory: [{ revision: 1, revisionDigest: 'sha256:first', createdAt: 'now' }], approval: null }
function show(active = true) { render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><ReviewReportEditor sessionId="session" runId="run" sessionActive={active} /></QueryClientProvider>) }
const ok = (value: unknown) => ({ ok: true, json: async () => value })
afterEach(() => { vi.unstubAllGlobals(); sessionStorage.clear() })
describe('ReviewReportEditor', () => {
 it('does not create on GET and reuses the exact request after an uncertain write', async () => {
  let created = false, attempts = 0
  const fetch = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
   if (url.endsWith("/handoffs")) return ok([])
   if (init?.method === 'POST') { attempts++; if (attempts === 1) return { ok: false, status: 503, json: async () => ({ status: 503, code: 'REVIEW_STATE_WRITE_UNCERTAIN', message: 'Write uncertain' }) }; created = true; return ok(view) }
   return created ? ok(view) : { ok: false, status: 404, json: async () => ({ status: 404, code: 'REPORT_NOT_FOUND', message: 'Not started' }) }
  })
  vi.stubGlobal('fetch', fetch); show()
  const create = await screen.findByRole('button', { name: '开始人工审阅' })
  expect(fetch.mock.calls.every(([, init]) => !init.method)).toBe(true)
  fireEvent.click(create)
  await screen.findByRole('button', { name: '重试同一请求' })
  fireEvent.click(screen.getByRole('button', { name: '读取当前版本' }))
  fireEvent.click(screen.getByRole('button', { name: '重试同一请求' }))
  await screen.findByDisplayValue('Original title')
  const sent = fetch.mock.calls.filter(([, init]) => init.method === 'POST')
  expect(sent).toHaveLength(2); expect(sent[0][1].body).toBe(sent[1][1].body)
 })
 it('saves a version with original grade and requires explicit approved selection and incomplete acknowledgement', async () => {
  let current = view
  const fetch = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
   if (url.endsWith("/handoffs")) return ok([])
   if (init?.method === 'PUT') {
    const body = JSON.parse(String(init.body)); current = { ...view, currentRevision: 2, reportRevision: { ...revision, ...body, revision: 2, revisionDigest: 'sha256:second' } }
   }
   return ok(current)
  })
  vi.stubGlobal('fetch', fetch); show()
  fireEvent.change(await screen.findByLabelText('标题 1'), { target: { value: 'Human title' } })
  fireEvent.change(screen.getByLabelText('裁决 1'), { target: { value: 'APPROVED_FOR_FIX' } })
  fireEvent.click(screen.getByRole('button', { name: '保存为新版本' }))
  await waitFor(() => expect(screen.getByText(/报告版本 2/)).toBeInTheDocument())
  expect(screen.getByText(/原始判断：待确认风险/)).toBeInTheDocument()
  const approve = screen.getByRole('button', { name: '保存批准选择' }); expect(approve).toBeDisabled()
  fireEvent.click(screen.getByLabelText('选择交付：Human title')); expect(approve).toBeDisabled()
  fireEvent.click(screen.getByLabelText('我确认本报告只有有限或不完整覆盖'))
  fireEvent.click(approve)
  await waitFor(() => expect(fetch.mock.calls.some(([url]) => String(url).endsWith('/approve'))).toBe(true))
  const saved = JSON.parse(String(fetch.mock.calls.find(([, init]) => init.method === 'PUT')![1].body))
  expect(saved.expectedRevision).toBe(1); expect(saved.findingReviews[0].originalDisposition).toBe('RISK')
  const approval = JSON.parse(String(fetch.mock.calls.find(([url]) => String(url).endsWith('/approve'))![1].body))
  expect(approval.expectedRevision).toBe(2); expect(approval.expectedRevisionDigest).toBe('sha256:second'); expect(approval.selectedFindingIds).toEqual(['f1'])
  expect(fetch.mock.calls.some(([url]) => String(url).includes('coding-turn'))).toBe(false)
 })
 it('reads an old version without publishing or enabling edits', async () => {
  const history = [{ revision: 1, revisionDigest: 'sha256:first', createdAt: 'then' }, { revision: 2, revisionDigest: 'sha256:second', createdAt: 'now' }]
  const fetch = vi.fn().mockImplementation(async (url: string) => url.endsWith("/handoffs") ? ok([]) : ok({ ...view, currentRevision: 2, revisionHistory: history,
    editable: !url.includes('revision=1'), reportRevision: url.includes('revision=1') ? revision : { ...revision, revision: 2, revisionDigest: 'sha256:second', findingReviews: [{ ...revision.findingReviews[0], title: 'New title' }] } }))
  vi.stubGlobal('fetch', fetch); show()
  await screen.findByDisplayValue('New title')
  fireEvent.change(screen.getByLabelText('查看版本'), { target: { value: '1' } })
  expect(await screen.findByDisplayValue('Original title')).toBeDisabled()
  expect(screen.getByRole('button', { name: '保存为新版本' })).toBeDisabled()
  expect(fetch.mock.calls.every(([, init]) => !init.method)).toBe(true)
 })
 it('restores an uncertain request without sending it until explicit retry', async () => {
  const held = { action: 'edit', body: { expectedRevision: 1, requestId: 'held-request', findingReviews: revision.findingReviews, userInstructions: 'Keep exact intent' } }
  sessionStorage.setItem('code-weave:report-intent:session:run', JSON.stringify(held))
  const fetch = vi.fn().mockImplementation(async (url: string) => ok(url.endsWith("/handoffs") ? [] : view)); vi.stubGlobal('fetch', fetch); show()
  await screen.findByDisplayValue('Original title')
  expect(fetch.mock.calls.every(([, init]) => !init.method)).toBe(true)
  fireEvent.click(screen.getByRole('button', { name: '重试同一请求' }))
  await waitFor(() => expect(fetch.mock.calls.some(([, init]) => init.method === 'PUT')).toBe(true))
  const sent = fetch.mock.calls.find(([, init]) => init.method === 'PUT')![1]
  expect(JSON.parse(String(sent.body))).toEqual(held.body)
 })
 it('allows correction after a confirmed validation rejection instead of locking the bad request', async () => {
  let attempts = 0
  const fetch = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
   if (url.endsWith("/handoffs")) return ok([])
   if (init?.method === 'PUT' && ++attempts === 1) return { ok: false, status: 400, json: async () => ({ status: 400, code: 'REPORT_REQUEST_INVALID', message: 'Invalid title' }) }
   return ok(view)
  })
  vi.stubGlobal('fetch', fetch); show()
  const title = await screen.findByLabelText('标题 1')
  fireEvent.change(title, { target: { value: '' } }); fireEvent.click(screen.getByRole('button', { name: '保存为新版本' }))
  await screen.findByText('Invalid title')
  expect(title).not.toBeDisabled()
  fireEvent.change(title, { target: { value: 'Corrected title' } }); fireEvent.click(screen.getByRole('button', { name: '保存为新版本' }))
  await waitFor(() => expect(attempts).toBe(2))
  const sent = fetch.mock.calls.filter(([, init]) => init.method === 'PUT').map(([, init]) => JSON.parse(String(init.body)))
  expect(sent[0].requestId).not.toBe(sent[1].requestId)
  expect(sessionStorage.getItem('code-weave:report-intent:session:run')).toBeNull()
 })
 it('keeps closed session reports read-only', async () => {
  vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string) => ok(url.endsWith("/handoffs") ? [] : { ...view, editable: false }))); show(false)
  expect(await screen.findByLabelText('标题 1')).toBeDisabled()
  expect(screen.getByRole('button', { name: '保存为新版本' })).toBeDisabled()
 })
})
