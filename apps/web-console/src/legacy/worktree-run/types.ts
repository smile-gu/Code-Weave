/** 旧版 Worktree Run 的执行状态。 */
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
