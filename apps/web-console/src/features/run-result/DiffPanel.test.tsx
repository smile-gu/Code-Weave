import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { WorkspaceChanges } from '../../shared/api/types'
import { DiffPanel, normalizeUnifiedDiff } from './DiffPanel'

const changes = (unifiedDiff: string): WorkspaceChanges => ({
  baseCommit: '41906e2',
  workspaceRoot: 'D:/runtime/worktrees/run-1',
  totalFiles: 1,
  unifiedDiffBytes: unifiedDiff.length,
  files: [{
    path: 'src/main/java/demo/Swap.java',
    oldPath: null,
    changeType: 'ADDED',
    binary: false,
  }],
  unifiedDiff,
})

describe('DiffPanel', () => {
  it('兼容 Diff 前方存在 Git 换行符诊断的历史数据', () => {
    const unifiedDiff = [
      "warning: in the working copy of 'Swap.java', LF will be replaced by CRLF",
      'diff --git a/src/main/java/demo/Swap.java b/src/main/java/demo/Swap.java',
      'new file mode 100644',
      'index 0000000..1234567',
      '--- /dev/null',
      '+++ b/src/main/java/demo/Swap.java',
      '@@ -0,0 +1 @@',
      '+public final class Swap {}',
      '',
    ].join('\n')

    expect(normalizeUnifiedDiff(unifiedDiff)).toMatch(/^diff --git/)
    render(<DiffPanel changes={changes(unifiedDiff)} />)

    expect(screen.getAllByText('src/main/java/demo/Swap.java').length).toBeGreaterThan(0)
    expect(screen.getByText('public final class Swap {}')).toBeInTheDocument()
  })

  it('解析异常时保留文件列表并显示友好提示', () => {
    render(<DiffPanel changes={changes('warning only')} />)

    expect(screen.getByText('src/main/java/demo/Swap.java')).toBeInTheDocument()
    expect(screen.getByText(/Diff 内容暂时无法解析/)).toBeInTheDocument()
  })
})
