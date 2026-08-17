import { describe, expect, it } from 'vitest'
import type { RunEvent, RunEventType } from '../../shared/api/types'
import { projectActivities, projectProcessOutput } from './activityProjection'

const event = (
  sequence: number,
  stage: string,
  type: RunEventType,
  payload: Record<string, unknown> = {},
): RunEvent => ({
  schemaVersion: 1,
  eventId: `event-${sequence}`,
  runId: 'run-1',
  stage,
  attempt: 1,
  sequence,
  timestamp: `2026-08-17T00:00:${String(sequence).padStart(2, '0')}Z`,
  type,
  payload,
})

describe('activityProjection', () => {
  it('merges a successful run into concise user activities', () => {
    const activities = projectActivities([
      event(1, 'run', 'RUN_CREATED', { baseRef: 'HEAD', taskPrompt: '修改 Greeting.java' }),
      event(2, 'run', 'RUN_QUEUED', { message: '任务已进入执行队列。' }),
      event(3, 'run', 'RUN_STARTED', { message: '任务开始执行。' }),
      event(4, 'worktree', 'STAGE_STARTED', { baseRef: 'HEAD' }),
      event(5, 'worktree', 'STAGE_COMPLETED', { resolvedCommit: '41906e2d68bfe9a7' }),
      event(6, 'coding', 'STAGE_STARTED'),
      event(7, 'coding', 'AGENT_EVENT_OBSERVED', {
        agentEventType: 'RUNTIME_INITIALIZED',
        payload: { tools: ['Read', 'Edit'] },
      }),
      event(8, 'coding', 'AGENT_EVENT_OBSERVED', {
        agentEventType: 'TOOL_CALL_STARTED', externalId: 'tool-1',
        payload: { name: 'Edit', input: { file_path: 'src/main/java/demo/Greeting.java' } },
      }),
      event(9, 'coding', 'AGENT_EVENT_OBSERVED', {
        agentEventType: 'TOOL_CALL_COMPLETED', externalId: 'tool-1',
        payload: { content: 'updated' },
      }),
      event(10, 'coding', 'STAGE_COMPLETED', { exitCode: 0 }),
      event(11, 'change-collection', 'STAGE_STARTED'),
      event(12, 'change-collection', 'STAGE_COMPLETED', { changedFiles: 1, diffBytes: 308 }),
      event(13, 'change-policy', 'STAGE_STARTED'),
      event(14, 'change-policy', 'STAGE_COMPLETED', { violations: 0 }),
      event(15, 'maven', 'STAGE_STARTED'),
      event(16, 'maven', 'PROCESS_START_REQUESTED', { command: ['mvn', 'clean', 'test'] }),
      event(17, 'maven', 'PROCESS_STARTED', { pid: 45120 }),
      event(18, 'maven', 'PROCESS_STDOUT', { preview: 'BUILD SUCCESS\n' }),
      event(19, 'maven', 'PROCESS_EXIT_OBSERVED', { exitCode: 0 }),
      event(20, 'maven', 'RUN_NOTICE', {
        code: 'NO_SUREFIRE_REPORTS', message: 'Maven 执行成功，但没有发现 Surefire 测试报告。',
      }),
      event(21, 'maven', 'ANALYSIS_COMPLETED', { status: 'PASSED', tests: 0, failures: 0, errors: 0 }),
      event(22, 'maven', 'STAGE_COMPLETED', { mavenStatus: 'PASSED', tests: 0 }),
      event(23, 'run', 'RUN_COMPLETED', { changedFiles: 1, mavenStatus: 'PASSED' }),
    ])

    expect(activities).toHaveLength(9)
    expect(activities.map((activity) => activity.title)).toEqual([
      '任务已开始执行',
      '隔离工作区准备完成',
      'Coding Agent 执行完成',
      '已修改 Greeting.java',
      '代码变更采集完成',
      '变更边界检查通过',
      'Maven 验证通过',
      '运行提示',
      'Coding Run 执行完成',
    ])
    expect(activities[3].sourceSequences).toEqual([8, 9])
    expect(activities[6].sourceSequences).toEqual([15, 16, 17, 18, 19, 21, 22])
    expect(activities[7]).toMatchObject({ status: 'warning' })
  })

  it('keeps tool failures visible and correlated with their start event', () => {
    const activities = projectActivities([
      event(1, 'coding', 'AGENT_EVENT_OBSERVED', {
        agentEventType: 'TOOL_CALL_STARTED', externalId: 'tool-7',
        payload: { name: 'Bash', input: { command: 'mvn test' } },
      }),
      event(2, 'coding', 'AGENT_EVENT_OBSERVED', {
        agentEventType: 'TOOL_CALL_FAILED', externalId: 'tool-7',
        payload: { content: 'command failed' },
      }),
    ])

    expect(activities).toHaveLength(2)
    expect(activities[1]).toMatchObject({
      title: '命令执行失败',
      summary: 'command failed',
      status: 'failed',
      sourceSequences: [1, 2],
    })
  })

  it('collects process output without exposing chunks as activities', () => {
    const events = [
      event(1, 'maven', 'PROCESS_STDOUT', { preview: 'line one\n' }),
      event(2, 'maven', 'PROCESS_STDERR', { preview: 'warning\n' }),
      event(3, 'maven', 'PROCESS_STDOUT', { preview: 'line two\n' }),
    ]

    expect(projectProcessOutput(events)).toEqual({
      chunks: [
        { sequence: 1, stream: 'stdout', text: 'line one\n' },
        { sequence: 2, stream: 'stderr', text: 'warning\n' },
        { sequence: 3, stream: 'stdout', text: 'line two\n' },
      ],
    })
    expect(projectActivities(events)).toHaveLength(1)
  })
})
