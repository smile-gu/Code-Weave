import { useMemo } from 'react'
import { Diff, Hunk, parseDiff } from 'react-diff-view'
import { FileCode2 } from 'lucide-react'
import type { RunChanges } from '../../shared/api/types'

/** 使用成熟 Unified Diff 组件展示本次 Run 的代码修改。 */
export function DiffPanel({ changes }: { changes: RunChanges }) {
  const parsedFiles = useMemo(() => parseDiff(changes.unifiedDiff), [changes.unifiedDiff])

  if (changes.totalFiles === 0) {
    return <div className="empty-state">任务已完成变更采集，但没有发现文件修改。</div>
  }
  return (
    <div className="diff-content">
      <aside className="changed-files">
        <div className="section-heading"><span>变更文件</span><small>{changes.totalFiles}</small></div>
        {changes.files.map((file) => (
          <div className="changed-file" key={`${file.oldPath}-${file.path}`}>
            <FileCode2 size={15} />
            <span title={file.path}>{file.path}</span>
            <b>{file.changeType.slice(0, 1)}</b>
          </div>
        ))}
      </aside>
      <div className="diff-files">
        {parsedFiles.map((file, index) => (
          <section className="diff-file" key={`${file.oldPath}-${file.newPath}-${index}`}>
            <header>
              <FileCode2 size={16} />
              <strong>{file.newPath || file.oldPath}</strong>
              <span>{file.type}</span>
            </header>
            <Diff viewType="unified" diffType={file.type} hunks={file.hunks}>
              {(hunks) => hunks.map((hunk) => <Hunk key={hunk.content} hunk={hunk} />)}
            </Diff>
          </section>
        ))}
      </div>
    </div>
  )
}
