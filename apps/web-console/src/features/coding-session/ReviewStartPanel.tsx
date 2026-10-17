import { useRef, useState } from 'react'
import { previewReview, readReviewPrice, startReview, type ReviewScope, type ReviewPrice } from '../../shared/api/reviewApi'
import { ApiError } from '../../shared/api/http'

const passes = { correctness: '正确性', consistency: '一致性', security: '安全', performance: '性能', architecture: '架构', testing: '测试' }
export function ReviewStartPanel({ sessionId, blocked, onStarted }: { sessionId: string; blocked: boolean; onStarted: () => void }) {
  const key = `review-start-unknown:${sessionId}`
  const [unknown, setUnknown] = useState(() => sessionStorage.getItem(key) === '1')
  const [preview, setPreview] = useState<{ scope: ReviewScope; price: ReviewPrice } | null>(null)
  const [selected, setSelected] = useState<string[]>(Object.keys(passes))
  const [amount, setAmount] = useState('2')
  const [message, setMessage] = useState('依据仓库业务契约审查当前修改，区分缺陷与良性重构，给出可核对的依据。')
  const [confirmed, setConfirmed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const lock = useRef(false)
  async function prepare() {
    if (lock.current) return
    lock.current = true; setBusy(true); setPreview(null); setConfirmed(false); setError('')
    try { const scope = await previewReview(sessionId); const price = await readReviewPrice(sessionId); setPreview({ scope, price }) }
    catch (e) { setError(e instanceof Error ? e.message : '预览失败') }
    finally { lock.current = false; setBusy(false) }
  }
  async function submit() {
    if (!preview || !valid || lock.current) return
    lock.current = true; setBusy(true); setError('')
    try {
      // Persist before sending: a lost acknowledgement must never cause an automatic paid retry.
      sessionStorage.setItem(key, '1'); setUnknown(true)
      await startReview(sessionId, preview.scope, selected, message, amount, preview.price)
      sessionStorage.removeItem(key); setUnknown(false); setPreview(null); setConfirmed(false); onStarted()
    } catch (e) {
      // Explicit client rejection is not a lost acknowledgement. Require a fresh preview before another attempt.
      if (e instanceof ApiError && e.status >= 400 && e.status < 500) {
        sessionStorage.removeItem(key); setUnknown(false); setPreview(null); setConfirmed(false)
      }
      setError(e instanceof Error ? e.message : '启动未确认')
    }
    finally { lock.current = false; setBusy(false); onStarted() }
  }
  const valid = !blocked && !unknown && !busy && confirmed && selected.length > 0 && message.trim().length > 0
    && /^(?:[1-9][0-9]{0,11}(?:\.[0-9]{1,6})?|0\.(?!0{1,6}$)[0-9]{1,6})$/.test(amount)
    && !!preview && Date.parse(preview.price.priceProfile.expiresAt) > Date.now() && preview.scope.changedPaths.length > 0
  return <section className="session-changes">
    <h3>启动独立 Review</h3><p>无需先执行 Coding。审查活动基线以来的当前修改；不会启动 Coding。</p>
    {blocked && <p>会话已关闭、Coding 正在运行或已有未结束的审查，暂不能启动。</p>}
    {unknown && <p role="alert">上次启动未确认，禁止重复发送。请刷新审查历史并核对服务端记录；刷新页面不会解除保护。</p>}
    <button type="button" disabled={blocked || busy || unknown} onClick={() => void prepare()}>预览审查范围与价格</button>
    {error && <p role="alert">{error}</p>}
    {preview && <div>
      <p>分支：{preview.scope.branch} · 基线：{preview.scope.activeBaselineCommit} · HEAD：{preview.scope.headCommit}</p>
      <ul>{preview.scope.changedPaths.map(path => <li key={path}>{path}</li>)}</ul>
      {!preview.scope.changedPaths.length && <p>没有可审查的修改。</p>}
      <fieldset disabled={busy || unknown || blocked}><legend>审查维度</legend>{Object.entries(passes).map(([id, label]) => <label key={id}><input type="checkbox" checked={selected.includes(id)} onChange={e => { setConfirmed(false); setSelected(e.target.checked ? [...selected, id] : selected.filter(p => p !== id)) }} />{label} </label>)}</fieldset>
      <p>{preview.price.priceProfile.model} · CNY／百万 Token：输入 {preview.price.priceProfile.inputCnyPerMillion}，输出 {preview.price.priceProfile.outputCnyPerMillion}。管理保守计费，不等同实际账单。价格有效至 {preview.price.priceProfile.expiresAt}。</p>
      <label>本轮费用上限（元）<input value={amount} disabled={busy || unknown} onChange={e => { setAmount(e.target.value); setConfirmed(false) }} inputMode="decimal" /></label>
      <label>审查要求<textarea value={message} disabled={busy || unknown} onChange={e => { setMessage(e.target.value); setConfirmed(false) }} /></label>
      <label><input type="checkbox" checked={confirmed} disabled={busy || unknown} onChange={e => setConfirmed(e.target.checked)} />我确认上述范围、维度与费用上限，授权本轮付费审查</label>
      <button type="button" disabled={!valid} onClick={() => void submit()}>确认启动 Review</button>
    </div>}
  </section>
}
