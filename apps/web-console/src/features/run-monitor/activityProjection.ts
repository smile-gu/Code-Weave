import type { RunEvent } from '../../shared/api/types'

export type ActivityStatus = 'running' | 'success' | 'warning' | 'failed'
export type ActivityCategory = 'run' | 'workspace' | 'agent' | 'change' | 'verification'

export type ActivityDetail = {
  label: string
  value: string
}

export type ActivityItem = {
  id: string
  category: ActivityCategory
  title: string
  summary: string
  status: ActivityStatus
  timestamp: string
  sourceSequences: number[]
  details: ActivityDetail[]
}

export type ProcessOutput = {
  chunks: ProcessOutputChunk[]
}

export type ProcessOutputChunk = {
  sequence: number
  stream: 'stdout' | 'stderr'
  text: string
}

type JsonRecord = Record<string, unknown>

const stageNames: Record<string, string> = {
  worktree: '隔离工作区',
  coding: 'Coding Agent',
  'change-collection': '代码变更采集',
  'change-policy': '变更边界检查',
  maven: 'Maven 验证',
}

const categoryByStage: Record<string, ActivityCategory> = {
  worktree: 'workspace',
  coding: 'agent',
  'change-collection': 'change',
  'change-policy': 'change',
  maven: 'verification',
}

/** 将完整事实事件投影为面向用户的执行活动。 */
export function projectActivities(events: RunEvent[]): ActivityItem[] {
  const activities: ActivityItem[] = []
  const byId = new Map<string, ActivityItem>()

  const upsert = (
    id: string,
    event: RunEvent,
    initial: Omit<ActivityItem, 'id' | 'timestamp' | 'sourceSequences' | 'details'>,
  ) => {
    let activity = byId.get(id)
    if (!activity) {
      activity = {
        id,
        ...initial,
        timestamp: event.timestamp,
        sourceSequences: [],
        details: [],
      }
      byId.set(id, activity)
      activities.push(activity)
    }
    touch(activity, event)
    return activity
  }

  for (const event of events) {
    if (event.type === 'RUN_CREATED' || event.type === 'RUN_QUEUED' || event.type === 'RUN_STARTED') {
      projectRunLifecycle(event, upsert)
      continue
    }

    if (event.type === 'STAGE_STARTED' || event.type === 'STAGE_COMPLETED' || event.type === 'STAGE_FAILED') {
      projectStageLifecycle(event, upsert)
      continue
    }

    if (event.type === 'AGENT_EVENT_OBSERVED') {
      projectAgentEvent(event, upsert)
      continue
    }

    if (event.type.startsWith('PROCESS_') || event.type === 'LOG_CAPTURE_COMPLETED'
      || event.type === 'ARTIFACT_RECORDED' || event.type === 'ANALYSIS_COMPLETED') {
      projectVerificationEvent(event, upsert)
      continue
    }

    if (event.type === 'RUN_NOTICE') {
      const activity = upsert(`notice:${event.sequence}`, event, {
        category: event.stage === 'maven' ? 'verification' : 'run',
        title: '运行提示',
        summary: text(event.payload.message) ?? text(event.payload.code) ?? '系统记录了一条运行提示。',
        status: 'warning',
      })
      addDetail(activity, '提示代码', text(event.payload.code))
      continue
    }

    if (event.type === 'RUN_COMPLETED' || event.type === 'RUN_FAILED' || event.type === 'RUN_CANCELLED') {
      projectRunTerminal(event, upsert)
    }
  }

  return activities
}

/** 汇总 Maven 标准输出和错误输出，供独立实时终端展示。 */
export function projectProcessOutput(events: RunEvent[]): ProcessOutput {
  const chunks: ProcessOutputChunk[] = []

  for (const event of events) {
    if (event.type !== 'PROCESS_STDOUT' && event.type !== 'PROCESS_STDERR') continue
    const preview = typeof event.payload.preview === 'string' ? event.payload.preview : ''
    if (preview.length === 0) continue
    chunks.push({
      sequence: event.sequence,
      stream: event.type === 'PROCESS_STDERR' ? 'stderr' : 'stdout',
      text: preview,
    })
  }

  return { chunks }
}

/** 将创建、排队和启动事件合并为任务生命周期活动。 */
function projectRunLifecycle(
  event: RunEvent,
  upsert: ActivityUpsert,
) {
  const activity = upsert('run:lifecycle', event, {
    category: 'run',
    title: '任务已创建',
    summary: '正在准备执行 Coding Run。',
    status: 'running',
  })

  if (event.type === 'RUN_CREATED') {
    const baseRef = text(event.payload.baseRef) ?? '指定版本'
    const prompt = text(event.payload.taskPrompt)
    activity.title = '任务已创建'
    activity.summary = prompt ? `准备基于 ${baseRef} 执行：${prompt}` : `准备基于 ${baseRef} 执行任务。`
    addDetail(activity, '代码仓库', text(event.payload.repository))
    addDetail(activity, '基准版本', baseRef)
  } else if (event.type === 'RUN_QUEUED') {
    activity.title = '任务等待执行'
    activity.summary = text(event.payload.message) ?? '任务已进入执行队列。'
  } else {
    activity.title = '任务已开始执行'
    activity.summary = text(event.payload.message) ?? '已进入五阶段 Coding 工作流。'
    activity.status = 'success'
  }
}

/** 将同一阶段的开始、完成和失败事件合并为一项活动。 */
function projectStageLifecycle(event: RunEvent, upsert: ActivityUpsert) {
  const stageName = stageNames[event.stage] ?? event.stage
  const id = `stage:${event.stage}:${event.attempt}`
  const activity = upsert(id, event, {
    category: categoryByStage[event.stage] ?? 'run',
    title: `正在执行${stageName}`,
    summary: stageStartedSummary(event),
    status: 'running',
  })

  if (event.type === 'STAGE_STARTED') {
    activity.title = stageStartedTitle(event.stage)
    activity.summary = stageStartedSummary(event)
    activity.status = 'running'
  } else if (event.type === 'STAGE_COMPLETED') {
    activity.title = stageCompletedTitle(event.stage)
    activity.summary = stageCompletedSummary(event)
    activity.status = 'success'
    addStageDetails(activity, event)
  } else {
    activity.title = `${stageName}失败`
    activity.summary = text(event.payload.message) ?? text(event.payload.reason)
      ?? text(event.payload.code) ?? '该阶段未能正常完成。'
    activity.status = 'failed'
    addDetail(activity, '失败代码', text(event.payload.code))
  }
}

/** 将 Agent Runtime 事件转换为可读的代码操作活动。 */
function projectAgentEvent(event: RunEvent, upsert: ActivityUpsert) {
  const agentType = text(event.payload.agentEventType)
  const payload = record(event.payload.payload)
  const coding = upsert(`stage:coding:${event.attempt}`, event, {
    category: 'agent',
    title: 'Coding Agent 正在工作',
    summary: 'Agent 正在分析任务并修改代码。',
    status: 'running',
  })

  if (agentType === 'RUNTIME_INITIALIZED') {
    const tools = stringArray(payload.tools)
    coding.title = 'Claude Code 已启动'
    coding.summary = tools.length > 0
      ? `已连接 Coding Agent，可使用 ${tools.join('、')} 工具。`
      : '已连接 Coding Agent，开始处理代码任务。'
    addDetail(coding, 'Agent 会话', text(payload.session_id))
    return
  }

  if (agentType === 'RUNTIME_COMPLETED') {
    coding.title = 'Coding Agent 已完成代码处理'
    coding.summary = 'Agent 已返回正常终态，正在检查代码变更。'
    coding.status = 'success'
    return
  }

  if (agentType === 'RUNTIME_FAILED') {
    coding.title = 'Coding Agent 执行失败'
    coding.summary = text(payload.result) ?? text(payload.message) ?? 'Agent Runtime 返回失败终态。'
    coding.status = 'failed'
    return
  }

  if (agentType === 'USAGE_UPDATED') return

  if (agentType === 'MESSAGE_COMPLETED') {
    const message = text(payload.text)
    if (!message) return
    upsert(`agent:message:${event.sequence}`, event, {
      category: 'agent',
      title: 'Agent 阶段说明',
      summary: message,
      status: 'success',
    })
    return
  }

  if (agentType === 'DIAGNOSTIC') {
    upsert(`agent:diagnostic:${event.sequence}`, event, {
      category: 'agent',
      title: 'Agent 诊断提示',
      summary: text(payload.message) ?? 'Agent 记录了一条诊断信息。',
      status: 'warning',
    })
    return
  }

  if (!agentType?.startsWith('TOOL_CALL_') && agentType !== 'PERMISSION_DENIED') return

  const externalId = text(event.payload.externalId) ?? `sequence-${event.sequence}`
  const toolId = `agent:tool:${externalId}`
  const toolName = text(payload.name)
  const input = record(payload.input)
  const existingTool = upsert(toolId, event, {
    category: 'agent',
    title: toolTitle(toolName, input, 'running'),
    summary: toolSummary(toolName, input),
    status: 'running',
  })

  if (toolName) {
    addDetail(existingTool, '工具', toolName)
    addDetail(existingTool, '目标', toolTarget(toolName, input))
  }

  if (agentType === 'TOOL_CALL_COMPLETED') {
    existingTool.title = completedToolTitle(existingTool)
    existingTool.summary = toolResultSummary(payload, existingTool.summary)
    existingTool.status = 'success'
  } else if (agentType === 'TOOL_CALL_FAILED' || agentType === 'PERMISSION_DENIED') {
    existingTool.title = agentType === 'PERMISSION_DENIED' ? '操作被安全策略阻止' : failedToolTitle(existingTool)
    existingTool.summary = text(payload.content) ?? text(payload.message) ?? '工具调用没有成功完成。'
    existingTool.status = 'failed'
  }
}

/** 将 Maven 进程和分析事件合并为验证活动。 */
function projectVerificationEvent(event: RunEvent, upsert: ActivityUpsert) {
  const activity = upsert(`stage:maven:${event.attempt}`, event, {
    category: 'verification',
    title: '正在执行 Maven 验证',
    summary: '正在编译项目并执行测试。',
    status: 'running',
  })

  if (event.type === 'PROCESS_START_REQUESTED') {
    const command = stringArray(event.payload.command).join(' ')
    activity.title = '正在启动 Maven 验证'
    activity.summary = command ? `准备执行 ${command}` : '正在准备 Maven 编译与测试命令。'
    addDetail(activity, '执行命令', command)
  } else if (event.type === 'PROCESS_STARTED') {
    activity.title = 'Maven 正在编译和测试'
    activity.summary = '验证进程已启动，输出正在写入实时终端。'
    addDetail(activity, '进程 ID', value(event.payload.pid))
  } else if (event.type === 'PROCESS_EXIT_OBSERVED') {
    addDetail(activity, '退出码', value(event.payload.exitCode))
  } else if (event.type === 'PROCESS_TERMINATION_REQUESTED') {
    activity.title = '正在终止 Maven 进程'
    activity.summary = text(event.payload.reason) ?? '验证进程正在按序终止。'
    activity.status = 'warning'
  } else if (event.type === 'PROCESS_TERMINATED') {
    activity.title = 'Maven 进程已终止'
    activity.summary = text(event.payload.reason) ?? '验证进程已结束。'
    activity.status = 'failed'
  } else if (event.type === 'LOG_CAPTURE_COMPLETED') {
    const stdout = record(event.payload.stdout)
    const stderr = record(event.payload.stderr)
    addDetail(activity, '标准输出日志', text(stdout.relativePath))
    addDetail(activity, '错误输出日志', text(stderr.relativePath))
  } else if (event.type === 'ARTIFACT_RECORDED') {
    addDetail(activity, '验证产物', text(event.payload.path))
  } else if (event.type === 'ANALYSIS_COMPLETED') {
    const passed = text(event.payload.status) === 'PASSED'
    const tests = number(event.payload.tests) ?? 0
    activity.title = passed ? 'Maven 验证通过' : 'Maven 验证失败'
    activity.summary = passed
      ? tests > 0 ? `编译和测试通过，共执行 ${tests} 个测试。` : '构建通过，本次没有执行到测试。'
      : verificationFailureSummary(event)
    activity.status = passed ? 'success' : 'failed'
    addDetail(activity, '测试总数', String(tests))
    addDetail(activity, '失败数', value(event.payload.failures))
    addDetail(activity, '错误数', value(event.payload.errors))
  }
}

/** 将 Run 终态事件转换为最终结论活动。 */
function projectRunTerminal(event: RunEvent, upsert: ActivityUpsert) {
  const completed = event.type === 'RUN_COMPLETED'
  const cancelled = event.type === 'RUN_CANCELLED'
  const activity = upsert('run:terminal', event, {
    category: 'run',
    title: completed ? 'Coding Run 执行完成' : cancelled ? 'Coding Run 已取消' : 'Coding Run 执行失败',
    summary: completed ? completedRunSummary(event) : terminalFailureSummary(event),
    status: completed ? 'success' : cancelled ? 'warning' : 'failed',
  })
  addDetail(activity, '变更文件', value(event.payload.changedFiles))
  addDetail(activity, 'Maven 状态', text(event.payload.mavenStatus))
  addDetail(activity, '失败阶段', text(event.payload.failedStage))
  addDetail(activity, '失败代码', text(event.payload.failureCode) ?? text(event.payload.code))
}

/** 返回阶段运行中状态对应的用户标题。 */
function stageStartedTitle(stage: string) {
  if (stage === 'worktree') return '正在准备隔离工作区'
  if (stage === 'coding') return 'Coding Agent 正在工作'
  if (stage === 'change-collection') return '正在采集代码变更'
  if (stage === 'change-policy') return '正在检查变更边界'
  if (stage === 'maven') return '正在执行 Maven 验证'
  return `正在执行${stageNames[stage] ?? stage}`
}

/** 根据阶段开始载荷生成用户摘要。 */
function stageStartedSummary(event: RunEvent) {
  if (event.stage === 'worktree') {
    const baseRef = text(event.payload.baseRef) ?? '指定版本'
    return `正在从 ${baseRef} 创建独立 Git Worktree。`
  }
  if (event.stage === 'coding') return 'Coding Agent 仅在本次任务的隔离工作区内操作。'
  if (event.stage === 'change-collection') return '正在生成变更文件清单与 Unified Diff。'
  if (event.stage === 'change-policy') return '正在确认所有修改都位于允许范围内。'
  if (event.stage === 'maven') return '正在独立执行项目编译与测试。'
  return '该阶段正在执行。'
}

/** 返回阶段完成状态对应的用户标题。 */
function stageCompletedTitle(stage: string) {
  if (stage === 'worktree') return '隔离工作区准备完成'
  if (stage === 'coding') return 'Coding Agent 执行完成'
  if (stage === 'change-collection') return '代码变更采集完成'
  if (stage === 'change-policy') return '变更边界检查通过'
  if (stage === 'maven') return 'Maven 验证通过'
  return `${stageNames[stage] ?? stage}执行完成`
}

/** 根据阶段完成载荷生成结果摘要。 */
function stageCompletedSummary(event: RunEvent) {
  if (event.stage === 'worktree') {
    const commit = text(event.payload.resolvedCommit)
    return commit ? `已基于提交 ${commit.slice(0, 8)} 创建独立工作区。` : '独立工作区已经可以使用。'
  }
  if (event.stage === 'coding') {
    const exitCode = number(event.payload.exitCode)
    return exitCode == null ? 'Agent 已正常完成代码处理。' : `Agent 正常退出，退出码 ${exitCode}。`
  }
  if (event.stage === 'change-collection') {
    const files = number(event.payload.changedFiles) ?? 0
    const bytes = number(event.payload.diffBytes) ?? 0
    return `发现 ${files} 个变更文件，共生成 ${formatBytes(bytes)} Diff。`
  }
  if (event.stage === 'change-policy') {
    const violations = number(event.payload.violations) ?? 0
    return violations === 0 ? '未发现越界文件，所有修改均符合任务约束。' : `发现 ${violations} 项路径违规。`
  }
  if (event.stage === 'maven') {
    const tests = number(event.payload.tests) ?? 0
    return tests > 0 ? `编译与测试通过，共执行 ${tests} 个测试。` : '构建通过，本次没有执行到测试。'
  }
  return '该阶段已经完成。'
}

/** 将阶段结果中的关键字段加入技术详情。 */
function addStageDetails(activity: ActivityItem, event: RunEvent) {
  if (event.stage === 'worktree') {
    addDetail(activity, 'Worktree', text(event.payload.worktree))
    addDetail(activity, '基准提交', text(event.payload.resolvedCommit))
  } else if (event.stage === 'change-collection') {
    addDetail(activity, '变更文件数', value(event.payload.changedFiles))
    addDetail(activity, 'Diff 大小', formatBytes(number(event.payload.diffBytes) ?? 0))
  } else if (event.stage === 'change-policy') {
    addDetail(activity, '违规数量', value(event.payload.violations))
  } else if (event.stage === 'coding') {
    addDetail(activity, 'Agent 状态', text(event.payload.claudeStatus))
    addDetail(activity, '退出码', value(event.payload.exitCode))
  }
}

/** 根据 Agent 工具名称和状态生成操作标题。 */
function toolTitle(toolName: string | null, input: JsonRecord, state: ActivityStatus) {
  const target = toolTarget(toolName, input)
  const suffix = target ? ` ${shortPath(target)}` : ''
  if (toolName === 'Read') return `${state === 'running' ? '正在读取' : '已读取'}${suffix}`
  if (toolName === 'Edit' || toolName === 'Write') return `${state === 'running' ? '正在修改' : '已修改'}${suffix}`
  if (toolName === 'Grep' || toolName === 'Glob') return '正在搜索相关代码'
  if (toolName === 'Bash') return '正在执行命令'
  return toolName ? `正在调用 ${toolName}` : 'Agent 正在调用工具'
}

/** 将工具运行中标题转换为完成标题。 */
function completedToolTitle(activity: ActivityItem) {
  if (activity.title.startsWith('正在读取')) return activity.title.replace('正在读取', '已读取')
  if (activity.title.startsWith('正在修改')) return activity.title.replace('正在修改', '已修改')
  if (activity.title === '正在搜索相关代码') return '代码搜索完成'
  if (activity.title === '正在执行命令') return '命令执行完成'
  return activity.title.replace('正在调用', '已完成调用')
}

/** 将工具运行中标题转换为失败标题。 */
function failedToolTitle(activity: ActivityItem) {
  if (activity.title.startsWith('正在修改')) return activity.title.replace('正在修改', '修改失败：')
  if (activity.title.startsWith('正在读取')) return activity.title.replace('正在读取', '读取失败：')
  if (activity.title === '正在执行命令') return '命令执行失败'
  return 'Agent 工具调用失败'
}

/** 根据工具输入生成可读的操作摘要。 */
function toolSummary(toolName: string | null, input: JsonRecord) {
  const target = toolTarget(toolName, input)
  if (toolName === 'Edit' || toolName === 'Write') return target ? `正在更新 ${target}。` : '正在写入代码文件。'
  if (toolName === 'Read') return target ? `正在读取 ${target}。` : '正在读取项目文件。'
  if (toolName === 'Grep' || toolName === 'Glob') return target ? `正在项目中搜索 ${target}。` : '正在定位与任务相关的代码。'
  if (toolName === 'Bash') return target ? `正在执行：${target}` : '正在执行项目命令。'
  return toolName ? `Agent 正在使用 ${toolName} 工具。` : 'Agent 正在执行一项代码操作。'
}

/** 优先使用工具返回内容生成完成摘要。 */
function toolResultSummary(payload: JsonRecord, fallback: string) {
  const result = text(payload.content)
  return result && result !== 'updated' ? result : fallback.replace('正在', '已完成：')
}

/** 从不同工具输入中提取文件、命令或搜索目标。 */
function toolTarget(toolName: string | null, input: JsonRecord) {
  if (toolName === 'Bash') return text(input.command)
  if (toolName === 'Grep') return text(input.pattern)
  if (toolName === 'Glob') return text(input.pattern)
  return text(input.file_path) ?? text(input.path)
}

/** 根据 Maven 分析结果生成失败说明。 */
function verificationFailureSummary(event: RunEvent) {
  const failures = number(event.payload.failures) ?? 0
  const errors = number(event.payload.errors) ?? 0
  if (failures + errors > 0) return `测试未通过：${failures} 个失败，${errors} 个错误。`
  return text(event.payload.message) ?? 'Maven 编译或测试未通过。'
}

/** 根据变更数量和 Maven 状态生成成功结论。 */
function completedRunSummary(event: RunEvent) {
  const files = number(event.payload.changedFiles) ?? 0
  const maven = text(event.payload.mavenStatus)
  if (maven === 'PASSED') return `修改 ${files} 个文件，Maven 验证通过。`
  if (maven === 'FAILED') return `修改 ${files} 个文件，但 Maven 验证未通过。`
  return `任务完成，共修改 ${files} 个文件。`
}

/** 从终态载荷中选择最具体的失败原因。 */
function terminalFailureSummary(event: RunEvent) {
  return text(event.payload.message) ?? text(event.payload.reason)
    ?? text(event.payload.failureCode) ?? text(event.payload.code) ?? '任务未能正常完成。'
}

/** 将新事实事件关联到已有活动并刷新活动时间。 */
function touch(activity: ActivityItem, event: RunEvent) {
  activity.timestamp = event.timestamp
  if (!activity.sourceSequences.includes(event.sequence)) activity.sourceSequences.push(event.sequence)
}

/** 新增或更新一项非空技术详情。 */
function addDetail(activity: ActivityItem, label: string, detailValue: string | null) {
  if (!detailValue) return
  const existing = activity.details.find((detail) => detail.label === label)
  if (existing) existing.value = detailValue
  else activity.details.push({ label, value: detailValue })
}

/** 将未知载荷安全收窄为 JSON 对象。 */
function record(candidate: unknown): JsonRecord {
  return candidate && typeof candidate === 'object' && !Array.isArray(candidate)
    ? candidate as JsonRecord
    : {}
}

/** 提取并清理非空字符串值。 */
function text(candidate: unknown): string | null {
  return typeof candidate === 'string' && candidate.trim() ? candidate.trim() : null
}

/** 提取有限数字值。 */
function number(candidate: unknown): number | null {
  return typeof candidate === 'number' && Number.isFinite(candidate) ? candidate : null
}

/** 将可展示的标量值转换为字符串。 */
function value(candidate: unknown): string | null {
  if (typeof candidate === 'number' || typeof candidate === 'boolean') return String(candidate)
  return text(candidate)
}

/** 从未知载荷中提取字符串数组。 */
function stringArray(candidate: unknown): string[] {
  return Array.isArray(candidate) ? candidate.filter((item): item is string => typeof item === 'string') : []
}

/** 从跨平台路径中提取末级名称。 */
function shortPath(path: string) {
  const normalized = path.replaceAll('\\', '/')
  const segments = normalized.split('/')
  return segments.at(-1) ?? path
}

/** 将字节数格式化为紧凑的用户文案。 */
function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  return `${(bytes / 1024).toFixed(1)} KB`
}

type ActivityUpsert = (
  id: string,
  event: RunEvent,
  initial: Omit<ActivityItem, 'id' | 'timestamp' | 'sourceSequences' | 'details'>,
) => ActivityItem
