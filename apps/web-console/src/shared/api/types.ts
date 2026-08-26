export type RunExecutionStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'
  | 'INFRASTRUCTURE_FAILED'

export type CreateRunInput = {
  repository: string
  baseRef: string
  taskPrompt: string
  acceptanceCriteria: string[]
  allowedPaths: string[]
  deniedPaths: string[]
}

export type CreateRunResponse = {
  runId: string
  status: RunExecutionStatus
  statusUrl: string
  eventsUrl: string
}

export type RunStatus = {
  runId: string
  status: RunExecutionStatus
  createdAt: string
  startedAt: string | null
  endedAt: string | null
  message: string
  failedStage: string | null
  failureCode: string | null
  worktree: string | null
  changedFiles: number | null
  mavenStatus: string | null
}

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

export type CodingSummary = {
  status: string
  exitCode: number | null
  initialized: boolean
  terminalResultReceived: boolean
  startedAt: string
  endedAt: string
}

export type PolicyViolation = {
  path: string
  changeType: string
  violationType: string
  matchedRule: string | null
  message: string
}

export type TestFailure = {
  testClass: string | null
  testName: string | null
  type: string | null
  message: string | null
}

export type MavenSummary = {
  status: string
  exitCode: number | null
  startedAt: string
  endedAt: string
  message: string
  tests: {
    reportFiles: number
    total: number
    passed: number
    failures: number
    errors: number
    skipped: number
    durationSeconds: number
    failedTests: TestFailure[]
    failureDetailsTruncated: boolean
  } | null
}

export type RunResult = {
  runId: string
  status: 'COMPLETED' | 'FAILED' | 'CANCELLED'
  startedAt: string
  endedAt: string
  failedStage: string | null
  failureCode: string | null
  message: string
  worktree: string | null
  changesAvailable: boolean
  changedFiles: number | null
  coding: CodingSummary | null
  changePolicy: {
    compliant: boolean
    violations: PolicyViolation[]
  } | null
  maven: MavenSummary | null
}

export type ChangedFile = {
  path: string
  oldPath: string | null
  changeType: string
  binary: boolean
}

export type RunChanges = {
  runId: string
  baseCommit: string
  worktree: string
  totalFiles: number
  unifiedDiffBytes: number
  files: ChangedFile[]
  unifiedDiff: string
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
export type MergeReadiness = {
  sessionId: string
  ready: boolean
  workspaceFingerprint: string
  sourceHead: string
  targetBranch: string
  changedFiles: number
  blockers: string[]
}

export type IntegrationStatus = 'MERGING' | 'MERGED' | 'INTERRUPTED' | 'NEEDS_ATTENTION'
export type CodingSessionIntegration = {
  sessionId: string
  status: IntegrationStatus
  sourceHeadBefore: string
  targetBranch: string
  workspaceFingerprint: string
  sessionCommit: string | null
  mergedHead: string | null
  startedAt: string
  updatedAt: string
  failureCode: string | null
  failureMessage: string | null
}

export type CodingSession = {
  sessionId: string
  title: string
  status: CodingSessionStatus
  sourceRepository: string
  worktreePath: string | null
  baseRef: string
  resolvedCommit: string | null
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
