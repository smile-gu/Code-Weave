import type { RunEvent, RunEventType } from '../../shared/api/types'

export type StageKey = 'worktree' | 'workspace' | 'coding' | 'change-collection' | 'change-policy' | 'maven'
export type StageStatus = 'pending' | 'running' | 'completed' | 'failed'

export type StageView = {
  key: StageKey
  label: string
  description: string
  status: StageStatus
  startedAt: string | null
  endedAt: string | null
  /** 当前阶段最新的用户可读进展；没有时回退到固定描述。 */
  summary?: string
}

const stageDefinitions: Omit<StageView, 'status' | 'startedAt' | 'endedAt'>[] = [
  { key: 'worktree', label: '隔离工作区', description: '创建独立 Git Worktree' },
  { key: 'coding', label: 'Claude Code', description: '在受限目录内修改代码' },
  { key: 'change-collection', label: '变更采集', description: '生成文件清单与 Unified Diff' },
  { key: 'change-policy', label: '路径门禁', description: '确认改动未越过任务边界' },
  { key: 'maven', label: 'Maven 验证', description: '独立执行编译与测试' },
]

const terminalTypes = new Set<RunEventType>(['RUN_COMPLETED', 'RUN_FAILED', 'RUN_CANCELLED'])

const stageLabels: Record<string, string> = {
  run: 'Coding Run',
  worktree: '隔离工作区',
  coding: 'Coding Agent',
  'change-collection': '代码变更采集',
  'change-policy': '变更边界检查',
  maven: 'Maven 验证',
}

const eventLabels: Record<RunEventType, string> = {
  RUN_CREATED: '任务已创建',
  RUN_QUEUED: '任务进入执行队列',
  RUN_STARTED: '任务开始执行',
  RUN_NOTICE: '运行提示',
  AGENT_EVENT_OBSERVED: 'Agent 事件',
  STAGE_STARTED: '阶段开始',
  PROCESS_START_REQUESTED: '进程准备启动',
  PROCESS_STARTED: '进程已启动',
  PROCESS_STDOUT: '标准输出',
  PROCESS_STDERR: '错误输出',
  PROCESS_EXIT_OBSERVED: '进程退出',
  PROCESS_TERMINATION_REQUESTED: '请求终止进程',
  PROCESS_TERMINATED: '进程已终止',
  LOG_CAPTURE_COMPLETED: '日志采集完成',
  ARTIFACT_RECORDED: '验证产物已保存',
  ANALYSIS_COMPLETED: '验证结果分析完成',
  STAGE_COMPLETED: '阶段完成',
  STAGE_FAILED: '阶段失败',
  RUN_COMPLETED: 'Coding Run 执行完成',
  RUN_CANCELLED: 'Coding Run 已取消',
  RUN_FAILED: 'Coding Run 执行失败',
}

const agentEventLabels: Record<string, string> = {
  RUNTIME_INITIALIZED: 'Claude Code 已启动',
  USAGE_UPDATED: 'Agent 用量更新',
  MESSAGE_COMPLETED: 'Agent 输出阶段说明',
  TOOL_CALL_STARTED: 'Agent 发起工具调用',
  TOOL_CALL_COMPLETED: 'Agent 工具调用完成',
  TOOL_CALL_FAILED: 'Agent 工具调用失败',
  PERMISSION_DENIED: 'Agent 工具权限被拒绝',
  RUNTIME_COMPLETED: 'Claude Code 执行完成',
  RUNTIME_FAILED: 'Claude Code 执行失败',
  DIAGNOSTIC: 'Agent 诊断信息',
}

export type EventPresentationContext = {
  toolNamesByExternalId: ReadonlyMap<string, string>
}

/** 根据阶段事实事件生成五阶段只读状态。 */
export function projectStages(events: RunEvent[]): StageView[] {
  const stages: StageView[] = stageDefinitions.map((stage) => ({
    ...stage,
    status: 'pending' as StageStatus,
    startedAt: null,
    endedAt: null,
  }))
  const byKey = new Map(stages.map((stage) => [stage.key, stage]))

  for (const event of events) {
    const stage = byKey.get(event.stage as StageKey)
    if (!stage) continue
    if (event.type === 'STAGE_STARTED') {
      stage.status = 'running'
      stage.startedAt = event.timestamp
    } else if (event.type === 'STAGE_COMPLETED') {
      stage.status = 'completed'
      stage.endedAt = event.timestamp
    } else if (event.type === 'STAGE_FAILED') {
      stage.status = 'failed'
      stage.endedAt = event.timestamp
    }
  }
  return stages
}

/** 返回事件列表中最后一条 Run 终态事件。 */
export function terminalEvent(events: RunEvent[]) {
  return events.findLast((event) => terminalTypes.has(event.type)) ?? null
}

/** 从工具开始事件建立调用标识到工具名称的展示关联。 */
export function createEventPresentationContext(events: RunEvent[]): EventPresentationContext {
  const toolNamesByExternalId = new Map<string, string>()
  for (const event of events) {
    if (event.type !== 'AGENT_EVENT_OBSERVED'
      || text(event.payload.agentEventType) !== 'TOOL_CALL_STARTED') continue
    const externalId = text(event.payload.externalId)
    const toolName = text(record(event.payload.payload).name)
    if (externalId && toolName) toolNamesByExternalId.set(externalId, toolName)
  }
  return { toolNamesByExternalId }
}

/** 将稳定的技术事件类型映射为包含上下文的中文标题。 */
export function eventTitle(event: RunEvent, context?: EventPresentationContext): string {
  const stage = stageLabels[event.stage] ?? event.stage
  if (event.type === 'AGENT_EVENT_OBSERVED') {
    const agentType = text(event.payload.agentEventType)
    const payload = record(event.payload.payload)
    const externalId = text(event.payload.externalId)
    const label = agentType ? agentEventLabels[agentType] : undefined
    const base = label ?? (agentType ? `Agent 事件 · ${agentType}` : 'Agent 事件')
    if (agentType === 'TOOL_CALL_STARTED') {
      const toolName = text(payload.name)
      return toolName ? `${base} · ${toolName}` : base
    }
    if (agentType === 'TOOL_CALL_COMPLETED' || agentType === 'TOOL_CALL_FAILED') {
      const toolName = externalId ? context?.toolNamesByExternalId.get(externalId) : undefined
      return toolName ? `${base} · ${toolName}` : base
    }
    return base
  }
  if (event.type === 'PROCESS_STDOUT' || event.type === 'PROCESS_STDERR') {
    const fromByte = number(event.payload.fromByte)
    const toByte = number(event.payload.toByte)
    const range = fromByte !== undefined && toByte !== undefined
      ? `（${fromByte}–${toByte} 字节）`
      : ''
    return `${stage} · ${eventLabels[event.type]}${range}`
  }
  if (event.type === 'STAGE_STARTED' || event.type === 'STAGE_COMPLETED' || event.type === 'STAGE_FAILED'
    || event.type.startsWith('PROCESS_') || event.type === 'LOG_CAPTURE_COMPLETED'
    || event.type === 'ARTIFACT_RECORDED' || event.type === 'ANALYSIS_COMPLETED') {
    return `${stage} · ${eventLabels[event.type]}`
  }
  return eventLabels[event.type]
}

/** 仅在载荷提供新增信息时生成摘要，避免与标题重复。 */
export function eventSummary(event: RunEvent, context?: EventPresentationContext): string | undefined {
  if (event.type === 'AGENT_EVENT_OBSERVED') return agentEventSummary(event, context)
  if (event.type === 'PROCESS_STDOUT' || event.type === 'PROCESS_STDERR') {
    return text(event.payload.preview) ?? `收到 ${number(event.payload.bytes) ?? 0} 字节进程输出。`
  }
  if (event.type === 'STAGE_STARTED' || event.type === 'STAGE_COMPLETED'
    || event.type === 'STAGE_FAILED') return stageEventSummary(event)
  if (event.type === 'RUN_CREATED') {
    const baseRef = text(event.payload.baseRef) ?? '指定版本'
    const taskPrompt = text(event.payload.taskPrompt)
    return taskPrompt ? `准备基于 ${baseRef} 执行：${taskPrompt}` : `准备基于 ${baseRef} 执行任务。`
  }
  if (event.type === 'RUN_QUEUED' || event.type === 'RUN_STARTED') return undefined
  if (event.type === 'RUN_COMPLETED') {
    const changedFiles = number(event.payload.changedFiles)
    const mavenStatus = verificationStatus(text(event.payload.mavenStatus))
    const details = [
      changedFiles === undefined ? undefined : `修改 ${changedFiles} 个文件`,
      mavenStatus ? `Maven 验证${mavenStatus}` : undefined,
    ].filter(Boolean)
    return details.length > 0 ? `${details.join('，')}。` : undefined
  }
  if (event.type === 'PROCESS_STARTED') {
    const pid = number(event.payload.pid)
    return pid === undefined ? '验证进程已经启动。' : `验证进程已经启动，PID 为 ${pid}。`
  }
  if (event.type === 'PROCESS_START_REQUESTED') {
    const command = stringArray(event.payload.command)
    return command.length > 0 ? `即将执行：${command.join(' ')}` : '正在准备启动验证进程。'
  }
  if (event.type === 'PROCESS_EXIT_OBSERVED') {
    const exitCode = number(event.payload.exitCode)
    return exitCode === undefined ? '验证进程已经退出。' : `验证进程已经退出，退出码为 ${exitCode}。`
  }
  if (event.type === 'PROCESS_TERMINATION_REQUESTED' || event.type === 'PROCESS_TERMINATED') {
    const reason = text(event.payload.reason)
    return reason ? `终止原因：${reason}` : undefined
  }
  if (event.type === 'LOG_CAPTURE_COMPLETED') {
    return '标准输出和错误输出已形成可校验的完整日志。'
  }
  if (event.type === 'ARTIFACT_RECORDED') {
    const kind = text(event.payload.kind)
    const path = text(event.payload.path)
    if (kind && path) return `已保存 ${kind}：${path}`
    return path ? `已保存验证产物：${path}` : undefined
  }
  if (event.type === 'ANALYSIS_COMPLETED') {
    const status = verificationStatus(text(event.payload.status))
    const tests = number(event.payload.tests)
    if (status && tests !== undefined) return `验证结果${status}，识别到 ${tests} 项测试。`
    return status ? `验证结果${status}。` : undefined
  }
  if (event.type === 'RUN_NOTICE' || event.type === 'RUN_FAILED' || event.type === 'RUN_CANCELLED') {
    return text(event.payload.message) ?? text(event.payload.reason) ?? text(event.payload.code)
  }
  const payload = event.payload
  return text(payload.message) ?? text(payload.reason) ?? text(payload.code) ?? text(payload.status)
}

/** 生成一条具体 Agent 行为的中文摘要。 */
function agentEventSummary(event: RunEvent, context?: EventPresentationContext): string | undefined {
  const agentType = text(event.payload.agentEventType)
  const payload = record(event.payload.payload)
  const externalId = text(event.payload.externalId)
  if (agentType === 'RUNTIME_INITIALIZED') {
    const model = text(payload.model)
    const version = text(payload.claude_code_version)
    const details = [model, version ? `Claude Code ${version}` : undefined].filter(Boolean)
    return details.length > 0 ? `已连接 ${details.join('，')}。` : 'Claude Code 已完成初始化。'
  }
  if (agentType === 'TOOL_CALL_STARTED') {
    const toolName = text(payload.name) ?? '工具'
    const input = record(payload.input)
    const target = text(input.file_path) ?? text(input.path) ?? text(input.pattern) ?? text(input.command)
    return target ? `${toolName}：${target}` : `Agent 已发起 ${toolName} 调用。`
  }
  if (agentType === 'TOOL_CALL_COMPLETED') {
    const toolName = externalId ? context?.toolNamesByExternalId.get(externalId) : undefined
    return toolName ? `${toolName} 工具已成功返回。` : '工具调用已成功返回。'
  }
  if (agentType === 'TOOL_CALL_FAILED') {
    const toolName = externalId ? context?.toolNamesByExternalId.get(externalId) : undefined
    return text(payload.content) ?? (externalId
      ? `${toolName ?? '工具'}调用返回失败。`
      : '工具调用返回失败。')
  }
  if (agentType === 'MESSAGE_COMPLETED') {
    return text(payload.text) ?? 'Agent 输出了一段完整阶段说明。'
  }
  if (agentType === 'RUNTIME_COMPLETED') {
    const turns = number(payload.num_turns)
    const cost = number(payload.total_cost_usd)
    const details = [
      turns === undefined ? undefined : `${turns} 轮交互`,
      cost === undefined ? undefined : `报告费用 $${cost}`,
    ].filter(Boolean)
    return details.length > 0 ? `Claude Code 正常结束，共 ${details.join('，')}。` : 'Claude Code 正常结束。'
  }
  if (agentType === 'RUNTIME_FAILED') {
    return text(payload.result) ?? text(payload.message) ?? 'Claude Code 返回失败终态。'
  }
  if (agentType === 'USAGE_UPDATED') {
    const estimatedTokens = number(payload.estimated_tokens)
    return estimatedTokens === undefined ? 'Agent 更新了本次任务用量。' : `当前估算用量为 ${estimatedTokens} Token。`
  }
  return text(payload.message) ?? text(payload.result)
}

/** 根据阶段和产出字段生成非重复的阶段摘要。 */
function stageEventSummary(event: RunEvent): string | undefined {
  if (event.type === 'STAGE_FAILED') {
    return text(event.payload.message) ?? text(event.payload.reason) ?? text(event.payload.code)
  }
  if (event.type === 'STAGE_STARTED') {
    if (event.stage === 'worktree') {
      const baseRef = text(event.payload.baseRef) ?? '指定版本'
      return `正在基于 ${baseRef} 创建独立 Git Worktree。`
    }
    if (event.stage === 'coding') return 'Claude Code 将在隔离 Worktree 中分析任务并修改代码。'
    if (event.stage === 'change-collection') return '正在读取 Git 变更并生成文件清单与 Unified Diff。'
    if (event.stage === 'change-policy') return '正在检查实际代码变更是否超出允许路径。'
    if (event.stage === 'maven') return '正在独立执行 Maven 编译与测试。'
    return undefined
  }
  if (event.stage === 'worktree') {
    const commit = text(event.payload.resolvedCommit)
    return commit ? `隔离 Worktree 已创建，基准提交为 ${commit.slice(0, 12)}。` : '隔离 Worktree 已创建。'
  }
  if (event.stage === 'coding') {
    const status = text(event.payload.claudeStatus)
    const exitCode = number(event.payload.exitCode)
    if (status && exitCode !== undefined) return `Claude Code 状态为 ${status}，退出码为 ${exitCode}。`
    return status ? `Claude Code 状态为 ${status}。` : undefined
  }
  if (event.stage === 'change-collection') {
    const changedFiles = number(event.payload.changedFiles)
    const diffBytes = number(event.payload.diffBytes)
    if (changedFiles !== undefined && diffBytes !== undefined) {
      return `采集到 ${changedFiles} 个变更文件，Unified Diff 为 ${diffBytes} 字节。`
    }
    return changedFiles === undefined ? undefined : `采集到 ${changedFiles} 个变更文件。`
  }
  if (event.stage === 'change-policy') {
    const violations = number(event.payload.violations)
    return violations === undefined
      ? undefined
      : violations === 0 ? '路径检查通过，未发现越界修改。' : `发现 ${violations} 项路径越界修改。`
  }
  if (event.stage === 'maven') {
    const status = verificationStatus(text(event.payload.mavenStatus))
    const tests = number(event.payload.tests)
    if (status && tests !== undefined) return `Maven 验证${status}，识别到 ${tests} 项测试。`
    return status ? `Maven 验证${status}。` : undefined
  }
  return undefined
}

/** 将未知 JSON 值安全转换为对象。 */
function record(value: unknown): Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {}
}

/** 提取非空字符串。 */
function text(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value.trim() : undefined
}

/** 提取有限数值。 */
function number(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

/** 提取字符串数组。 */
function stringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : []
}

/** 将验证状态转换为用户可读结论。 */
function verificationStatus(value: string | undefined): string | undefined {
  if (value === 'PASSED' || value === 'COMPLETED') return '通过'
  if (value === 'FAILED') return '失败'
  if (value === 'CANCELLED') return '已取消'
  return value
}
