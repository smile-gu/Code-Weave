import { request } from './http'

export type ReviewScope = { activeBaselineCommit: string; headCommit: string; branch: string; workspaceFingerprint: string; changedPaths: string[] }
export type ReviewPrice = { digest: string; priceProfile: { priceProfileId: string; model: string; currency: string; inputCnyPerMillion: string; outputCnyPerMillion: string; expiresAt: string } }
export function previewReview(sessionId: string) {
  return request<ReviewScope>(`/api/v1/coding-sessions/${encodeURIComponent(sessionId)}/review-scope-preview`)
}
export function readReviewPrice(sessionId: string) {
  return request<ReviewPrice>(`/api/v1/coding-sessions/${encodeURIComponent(sessionId)}/review-price-profile`)
}
export function startReview(sessionId: string, scope: ReviewScope, selectedPasses: string[], message: string, maxAmount: string, price: ReviewPrice) {
  return request<ReviewRun>(`/api/v1/coding-sessions/${encodeURIComponent(sessionId)}/review-runs`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ expectedActiveBaselineCommit: scope.activeBaselineCommit, expectedHeadCommit: scope.headCommit,
      expectedBranch: scope.branch, expectedWorkspaceFingerprint: scope.workspaceFingerprint, targetPaths: scope.changedPaths,
      input: { message }, selectedPasses, feeAuthorization: { authorizationId: crypto.randomUUID(), currency: 'CNY', maxAmount,
        confirmedAt: new Date().toISOString().replace(/(\.\d{3})Z$/, '$1000Z'), priceProfileId: price.priceProfile.priceProfileId, priceProfileDigest: price.digest } }),
  })
}

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


export type FindingDecision = 'PENDING' | 'APPROVED_FOR_FIX' | 'DEFERRED' | 'ACCEPTED_RISK' | 'DISMISSED'
export type FindingReview = { findingId: string; originalDisposition: string; title: string; description: string; fixInstructions: string; note: string; decision: FindingDecision }
export type ReportApproval = { requestId: string; revision: number; revisionDigest: string; selectedFindingIds: string[]; incompleteAcknowledged: boolean; userInstructions: string; createdAt: string }
export type ReportRevision = {
  reportVersion: '1.0'; reportId: string; revision: number; sessionId: string; reviewThreadId: string; runId: string;
  sourceResultId: string; sourceResultSha256: string; sourceResultVersion: string; snapshotId: string; manifestSha256: string;
  createdAt: string; findingReviews: FindingReview[]; userInstructions: string; revisionDigest: string; requestId: string; requestDigest: string;
}
export type ReportView = { reportRevision: ReportRevision; currentRevision: number; editable: boolean; interaction: 'OPEN' | 'CLOSED'; revisionHistory: { revision: number; revisionDigest: string; createdAt: string }[]; approval: ReportApproval | null }
export type ReportEdit = { expectedRevision: number; requestId: string; findingReviews: FindingReview[]; userInstructions: string }
export type ReportApprove = { expectedRevision: number; expectedRevisionDigest: string; requestId: string; selectedFindingIds: string[]; incompleteAcknowledged: boolean; userInstructions: string }
export type ReportIntent = { action: 'create'; body: { requestId: string } } | { action: 'edit'; body: ReportEdit } | { action: 'approve'; body: ReportApprove } | { action: 'close'; body: { expectedRevision: number; requestId: string } }
function reportPath(sessionId: string, runId: string) { return `/api/v1/coding-sessions/${encodeURIComponent(sessionId)}/review-runs/${encodeURIComponent(runId)}/report` }
export function readHumanReport(sessionId: string, runId: string, revision?: number) {
  return request<ReportView>(reportPath(sessionId, runId) + (revision === undefined ? '' : `?revision=${revision}`))
}
export async function writeHumanReport(sessionId: string, runId: string, intent: ReportIntent): Promise<void> {
  const suffix = intent.action === 'approve' ? '/approve' : intent.action === 'close' ? '/close' : ''
  await request(reportPath(sessionId, runId) + suffix, { method: intent.action === 'edit' ? 'PUT' : 'POST',
    headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(intent.body) })
}

export type HandoffStatus = 'PREPARED' | 'SUPERSEDED' | 'REVOKED' | 'STARTING' | 'STARTED' | 'START_BLOCKED'
export type HandoffContent = {
  handoffVersion: '1.0'; handoffId: string; sessionId: string; reviewThreadId: string; runId: string;
  snapshotId: string; manifestSha256: string; sourceResultId: string; sourceResultSha256: string;
  sourceResultVersion: string; reportId: string; reportRevision: number; reportRevisionDigest: string;
  approvalRequestId: string; selectedFindingIds: string[];
  materials: { review: FindingReview; originalFindingJson: string; evidenceJson: string[] }[];
  reportInstructions: string; approvalInstructions: string; coverageJson: string; createdAt: string;
  requestId: string; requestDigest: string; contentDigest: string;
}
export type HandoffView = { content: HandoffContent; lifecycle: { handoffId: string; contentDigest: string; status: HandoffStatus; turnId: string | null; startRequestId: string | null; startRequestDigest: string | null; reason: string | null; updatedAt: string } }
export type HandoffIntent = { action: 'prepare'; body: { requestId: string; expectedRevision: number; expectedRevisionDigest: string } }
  | { action: 'start' | 'revoke'; handoffId: string; body: { requestId: string; expectedHandoffDigest: string } }
function handoffPath(sessionId: string, runId: string) { return `/api/v1/coding-sessions/${encodeURIComponent(sessionId)}/review-runs/${encodeURIComponent(runId)}/handoffs` }
export function readHandoffs(sessionId: string, runId: string) { return request<HandoffView[]>(handoffPath(sessionId, runId)) }
export function writeHandoff(sessionId: string, runId: string, intent: HandoffIntent) {
  return request<HandoffView>(handoffPath(sessionId, runId) + (intent.action === 'prepare' ? '' : `/${encodeURIComponent(intent.handoffId)}/${intent.action}`),
    { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(intent.body) })
}
