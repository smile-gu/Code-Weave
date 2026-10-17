/** Presentation only: wire enums, stored Result and source text stay untouched. */
const labels: Record<string, string> = {
  COMPLETED: '已完成', INCOMPLETE: '未完成', SKIPPED: '未执行', FAILED: '失败', CANCELLED: '已取消',
  PREPARING: '准备中', SUBMITTING: '提交中', SUBMISSION_UNKNOWN: '提交未确认', ACTIVE: '审查中',
  FINALIZING: '交付中', EXECUTION_BLOCKED: '执行受阻', RUNNING: '运行中', READY: '待执行', PENDING: '等待中',
  FULL: '材料完整', PARTIAL: '材料部分覆盖', SAMPLED: '抽样材料',
  VERIFIED: '已验证', RISK: '待确认风险', SUGGESTION: '改进建议', REJECTED: '不采纳',
  CRITICAL: '严重', HIGH: '高', MEDIUM: '中', LOW: '低',
  CODE: '代码', DOMAIN_CONTEXT: '业务背景', NONE: '未脱敏', APPLIED: '已脱敏', NOT_APPLICABLE: '不适用',
  final: '最终源码', diff: '变更差异',
  USER_NOT_SELECTED: '用户未选择', RUN_BUDGET_EXHAUSTED: '总预算耗尽，尚未执行',
  SOURCE_COVERAGE_PARTIAL: '来源材料覆盖不足', REVIEW_COVERAGE_PARTIAL: '审查覆盖尚不完整',
  TARGET_SCOPE_PARTIAL: '目标范围未全部检查', NO_INSPECTED_MATERIAL: '没有已检查的材料',
  CANDIDATE_VALIDATION_INCOMPLETE: '部分候选问题尚未完成验证',
  REVIEW_RUN_INTERRUPTED: '审查运行中断', REVIEW_RUN_CANCELLED: '审查已取消',
  FEE_BUDGET_EXHAUSTED: '费用预算已耗尽', PASS_TIME_BUDGET_EXHAUSTED: '当前维度时间预算已耗尽',
  RUN_TIME_BUDGET_EXHAUSTED: '本轮时间预算已耗尽', PASS_CALL_BUDGET_EXHAUSTED: '当前维度调用预算已耗尽',
  RUN_CALL_BUDGET_EXHAUSTED: '本轮调用预算已耗尽',
  EVIDENCE_REFERENCE_INVALID: '证据引用未通过校验', PROVIDER_STRUCTURED_OUTPUT_INVALID: '模型返回格式不符合要求',
  PROVIDER_VALIDATION_INVALID: '模型验证回复不符合要求',
}
export function reviewLabel(value: string): string {
  const prefix = 'MODEL_OUTPUT_REJECTED_'
  return value.startsWith(prefix) ? `模型提交纠错未成功：${reviewLabel(value.slice(prefix.length))}` : labels[value] ?? value
}
