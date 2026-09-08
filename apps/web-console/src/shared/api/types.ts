export type RunEventType =
  | 'RUN_CREATED'
  | 'RUN_QUEUED'
  | 'RUN_STARTED'
  | 'RUN_NOTICE'
  | 'AGENT_EVENT_OBSERVED'
  | 'STAGE_STARTED'
  | 'PROCESS_START_REQUESTED'
  | 'PROCESS_STARTED'
  | 'PROCESS_STDOUT'
  | 'PROCESS_STDERR'
  | 'PROCESS_EXIT_OBSERVED'
  | 'PROCESS_TERMINATION_REQUESTED'
  | 'PROCESS_TERMINATED'
  | 'LOG_CAPTURE_COMPLETED'
  | 'ARTIFACT_RECORDED'
  | 'ANALYSIS_COMPLETED'
  | 'STAGE_COMPLETED'
  | 'STAGE_FAILED'
  | 'STAGE_SKIPPED'
  | 'RUN_COMPLETED'
  | 'RUN_CANCELLED'
  | 'RUN_FAILED'

export type RunEvent = {
  schemaVersion: number
  eventId: string
  runId: string
  stage: string
  attempt: number
  sequence: number
  timestamp: string
  type: RunEventType
  payload: Record<string, unknown>
}

export type ChangedFile = {
  path: string
  oldPath: string | null
  changeType: string
  binary: boolean
}

type ChangesSnapshot = {
  baseCommit: string
  workspaceRoot: string
  totalFiles: number
  unifiedDiffBytes: number
  files: ChangedFile[]
  unifiedDiff: string
}

export type WorkspaceChanges = ChangesSnapshot

export type RunChanges = ChangesSnapshot & {
  runId: string
}

export type ApiErrorBody = {
  timestamp: string
  status: number
  code: string
  message: string
  path: string
  runId: string | null
  fieldErrors: Record<string, string>
}

export type RuntimeReadinessStatus = 'READY' | 'NOT_READY'
export type RuntimeCheckStatus = 'PASSED' | 'WARNING' | 'FAILED'
export type RuntimeComponent = 'RUNTIME_ROOT' | 'GIT' | 'JAVA' | 'MAVEN' | 'CLAUDE_CODE'

export type RuntimeCheck = {
  component: RuntimeComponent
  status: RuntimeCheckStatus
  required: boolean
  version: string | null
  executable: string | null
  message: string
  remediation: string | null
}

export type RuntimeReadiness = {
  status: RuntimeReadinessStatus
  checkedAt: string
  checks: RuntimeCheck[]
}

export type CodingSessionStatus = 'INITIALIZING' | 'ACTIVE' | 'LEGACY_READ_ONLY' | 'CLOSED' | 'MERGED' | 'FAILED'

export type CreateCodingSessionInput = {
  title: string | null
  repository: string
  baseRef: string
  allowedPaths: string[]
  deniedPaths: string[]
  includeExistingChanges?: boolean
}

export type TurnStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'INTERRUPTED'
export type TurnOutcome = 'NO_CHANGES' | 'CHANGES_VERIFIED' | 'VERIFICATION_FAILED' | 'POLICY_VIOLATION' | 'WORKSPACE_DRIFT' | 'AGENT_FAILED'
export type CodingTurn = {
  failedStage?: string | null
  failureKind?: string | null
  turnId: string
  sessionId: string
  sequence: number
  userPrompt: string
  status: TurnStatus
  outcome: TurnOutcome | null
  assistantMessage: string | null
  changedFiles: number
  workspaceFingerprint: string | null
  failureCode: string | null
  userMessage: string
  createdAt: string
  startedAt: string | null
  endedAt: string | null
}
export type CodingTurnList = { turns: CodingTurn[] }
export type ReviewBaselineReadiness = {
  sessionId: string
  ready: boolean
  activeBaselineCommit: string
  currentHead: string
  branch: string
  changedFiles: number
  workspaceFingerprint: string
  blockers: string[]
}

export type CodingSession = {
  sessionId: string
  title: string
  status: CodingSessionStatus
  sourceRepository: string
  worktreePath: string | null
  baseRef: string
  resolvedCommit: string | null
  activeReviewBaselineCommit: string | null
  sourceRepositoryDirty: boolean
  allowedPaths: string[]
  deniedPaths: string[]
  claudeSessionId?: string | null
  createdAt: string
  updatedAt: string
  failureCode: string | null
  failureMessage: string | null
}

export type CodingSessionList = {
  sessions: CodingSession[]
}
