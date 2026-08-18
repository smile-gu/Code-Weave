import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { RuntimeReadiness } from '../../shared/api/types'
import { RuntimeReadinessPanel } from './RuntimeReadinessPanel'

const ready: RuntimeReadiness = {
  status: 'READY',
  checkedAt: '2026-08-18T02:00:00Z',
  checks: [{
    component: 'CLAUDE_CODE',
    status: 'PASSED',
    required: true,
    version: '2.1.217',
    executable: 'D:/develop/nodejs/claude.cmd',
    message: 'Claude Code 已就绪，命令协议与平台兼容。',
    remediation: null,
  }],
}

describe('RuntimeReadinessPanel', () => {
  it('shows ready component version and configured executable', () => {
    render(<RuntimeReadinessPanel
      readiness={ready}
      loading={false}
      refreshing={false}
      error={null}
      onRefresh={vi.fn()}
    />)

    expect(screen.getByText('运行环境已就绪')).toBeInTheDocument()
    expect(screen.getByText('2.1.217')).toBeInTheDocument()
    expect(screen.getByText('D:/develop/nodejs/claude.cmd')).toBeInTheDocument()
  })

  it('shows actionable remediation for a failed component', () => {
    render(<RuntimeReadinessPanel
      readiness={{
        ...ready,
        status: 'NOT_READY',
        checks: [{
          ...ready.checks[0],
          status: 'FAILED',
          message: '当前启动入口不能由 Java 进程直接执行。',
          remediation: 'Windows 环境请配置 claude.cmd，而不是 claude.ps1。',
        }],
      }}
      loading={false}
      refreshing={false}
      error={null}
      onRefresh={vi.fn()}
    />)

    expect(screen.getByText('运行环境未就绪')).toBeInTheDocument()
    expect(screen.getByText(/而不是 claude\.ps1/)).toBeInTheDocument()
  })

  it('shows control plane connection failure without pretending readiness', () => {
    render(<RuntimeReadinessPanel
      readiness={undefined}
      loading={false}
      refreshing={false}
      error="请求失败，HTTP 503"
      onRefresh={vi.fn()}
    />)

    expect(screen.getByRole('alert')).toHaveTextContent('无法连接本机 Control Plane')
    expect(screen.queryByText('运行环境已就绪')).not.toBeInTheDocument()
  })
})
