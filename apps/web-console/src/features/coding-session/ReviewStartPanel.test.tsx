import { fireEvent, render, screen, waitFor, cleanup } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ReviewStartPanel } from './ReviewStartPanel'

const scope = { activeBaselineCommit: 'baseline', headCommit: 'head', branch: 'main', workspaceFingerprint: 'fingerprint', changedPaths: ['src/A.java'] }
const price = { digest: 'sha256:digest', priceProfile: { priceProfileId: 'price-1', model: 'deepseek-flash', inputCnyPerMillion: '2', outputCnyPerMillion: '8', expiresAt: '2099-01-01T00:00:00Z' } }
beforeEach(() => sessionStorage.clear())
afterEach(() => { cleanup(); vi.unstubAllGlobals() })
function setup(fail = false) {
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === 'POST' && fail) throw new Error('connection lost')
    return { ok: true, json: async () => url.endsWith('review-scope-preview') ? scope : url.endsWith('review-price-profile') ? price : { runId: 'r' } }
  })
  vi.stubGlobal('fetch', fetch)
  render(<ReviewStartPanel sessionId="s" blocked={false} onStarted={vi.fn()} />)
  return fetch
}
async function prepare() {
  fireEvent.click(screen.getByRole('button', { name: '预览审查范围与价格' }))
  await screen.findByText('src/A.java')
}
describe('ReviewStartPanel', () => {
  it('never starts on mount or preview; binds explicit authorization to scope and price', async () => {
    const fetch = setup(); expect(fetch).not.toHaveBeenCalled()
    await prepare()
    const baseline = screen.getByText('baseline')
    const technical = baseline.closest('details')!
    expect(technical).not.toHaveAttribute('open')
    fireEvent.click(technical.querySelector('summary')!)
    expect(technical).toHaveAttribute('open')
    const start = screen.getByRole('button', { name: '确认启动 Review' })
    expect(start).toBeDisabled()
    fireEvent.click(screen.getByLabelText(/我确认上述范围/))
    fireEvent.click(start)
    await waitFor(() => expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1))
    const body = JSON.parse(String(fetch.mock.calls.find(([, init]) => init?.method === 'POST')![1]!.body))
    expect(body.expectedWorkspaceFingerprint).toBe('fingerprint')
    expect(body.targetPaths).toEqual(['src/A.java'])
    expect(body.feeAuthorization).toMatchObject({ maxAmount: '2', priceProfileId: 'price-1', priceProfileDigest: 'sha256:digest' })
    expect(body.feeAuthorization.confirmedAt).toMatch(/\.\d{6}Z$/)
  })
  it('does not retry an unknown paid submission and preserves protection across remount', async () => {
    const fetch = setup(true); await prepare()
    fireEvent.click(screen.getByLabelText(/我确认上述范围/))
    fireEvent.click(screen.getByRole('button', { name: '确认启动 Review' }))
    await screen.findByText('connection lost')
    expect(screen.getByRole('button', { name: '确认启动 Review' })).toBeDisabled()
    expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
    cleanup(); render(<ReviewStartPanel sessionId="s" blocked={false} onStarted={vi.fn()} />)
    expect(screen.getByRole('button', { name: '预览审查范围与价格' })).toBeDisabled()
  })
  it('blocks closed or busy sessions without sending requests', () => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
    render(<ReviewStartPanel sessionId="s" blocked onStarted={vi.fn()} />)
    expect(screen.getByRole('button', { name: '预览审查范围与价格' })).toBeDisabled()
    expect(fetch).not.toHaveBeenCalled()
  })
  it.each(['empty', 'expired'])('blocks %s previews even after authorization', async mode => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => ({ ok: true, json: async () => url.endsWith('review-scope-preview')
      ? { ...scope, changedPaths: mode === 'empty' ? [] : scope.changedPaths }
      : { ...price, priceProfile: { ...price.priceProfile, expiresAt: mode === 'expired' ? '2000-01-01T00:00:00Z' : price.priceProfile.expiresAt } } })))
    render(<ReviewStartPanel sessionId="s" blocked={false} onStarted={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: '预览审查范围与价格' }))
    await screen.findByLabelText(/我确认上述范围/)
    fireEvent.click(screen.getByLabelText(/我确认上述范围/))
    expect(screen.getByRole('button', { name: '确认启动 Review' })).toBeDisabled()
  })
  it('shows unavailable provider errors without posting', async () => {
    const fetch = vi.fn(async () => ({ ok: false, status: 503, json: async () => ({ status: 503, code: 'REVIEW_HARNESS_NOT_READY', message: '审查服务未就绪' }) }))
    vi.stubGlobal('fetch', fetch)
    render(<ReviewStartPanel sessionId="s" blocked={false} onStarted={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: '预览审查范围与价格' }))
    await screen.findByText('审查服务未就绪')
    expect(screen.queryByRole('button', { name: '确认启动 Review' })).toBeNull()
    expect(fetch).toHaveBeenCalledTimes(1)
  })
})
