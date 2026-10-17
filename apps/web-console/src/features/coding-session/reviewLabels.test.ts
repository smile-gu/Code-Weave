import { describe, expect, it } from 'vitest'
import { reviewLabel } from './reviewLabels'

describe('review presentation labels', () => {
  it('localizes grades and states without modifying wire values', () => {
    const original = { severity: 'HIGH', disposition: 'VERIFIED', status: 'COMPLETED' }
    expect(reviewLabel(original.severity)).toBe('高')
    expect(reviewLabel(original.disposition)).toBe('已验证')
    expect(reviewLabel(original.status)).toBe('已完成')
    expect(original).toEqual({ severity: 'HIGH', disposition: 'VERIFIED', status: 'COMPLETED' })
  })
  it('preserves unknown diagnostics, prose and source identifiers verbatim', () => {
    for (const value of ['FUTURE_DIAGNOSTIC', 'Math.multiplyExact', 'src/A.java', 'An original English claim']) {
      expect(reviewLabel(value)).toBe(value)
    }
    expect(reviewLabel('MODEL_OUTPUT_REJECTED_EVIDENCE_REFERENCE_INVALID')).toBe('模型提交纠错未成功：证据引用未通过校验')
  })
})
