import { request } from './http'

export type ReviewRun = {
  runId: string
  snapshotId: string
  status: string
  simulationOnly: boolean
  diagnosticCode: string | null
  passProgress?: { passId: string; status: string; reason: string | null }[]
}
type CodeReference = { sourceType: 'CODE'; path: string; side: string; startLine: number; endLine: number }
type MaterialReference = CodeReference | { sourceType: 'ARTIFACT'; artifactId: string; startLine: number; endLine: number } | { sourceType: 'USER_INPUT'; sha256: string }
export type ReviewResult = {
  resultVersion: '1.0' | '2.0' | '3.0'
  reviewPlan?: { selectedPasses: string[] }
  coverageStatus?: 'SELECTED_COMPLETE' | 'SELECTED_INCOMPLETE'
  snapshotId: string
  passResults: { passId?: string; selected?: boolean; skipReason?: string | null; status: string; coverage: string; checkedScope: string[]; limitations: string[]; incompleteReasons: string[] }[]
  findings: { findingId: string; title: string; claim: string; disposition: string; severity: string; confidence: number; evidenceIds: string[]; codeReferences: CodeReference[]; contributions?: { passId: string; disposition: string }[] }[]
  evidence: { evidenceId: string; evidenceType: string; summary: string; coverage: string; sourceRefs: MaterialReference[] }[]
  reportDraft: { markdown: string }
}

export function listReviewRuns(sessionId: string) {
  return request<ReviewRun[]>(`/api/v1/coding-sessions/${encodeURIComponent(sessionId)}/review-runs?limit=50`)
}

/** Reads imported immutable history; never captures the current repository. */
export function readReviewResult(sessionId: string, runId: string) {
  return request<ReviewResult>(`/api/v1/coding-sessions/${encodeURIComponent(sessionId)}/review-runs/${encodeURIComponent(runId)}/result`)
}

export function readEvidenceMaterials(sessionId: string, runId: string, evidenceId: string) {
  return request<{ materials: { text: string; coverage: string; redactionState: string }[] }>(`/api/v1/coding-sessions/${encodeURIComponent(sessionId)}/review-runs/${encodeURIComponent(runId)}/evidence/${encodeURIComponent(evidenceId)}/materials`)
}
