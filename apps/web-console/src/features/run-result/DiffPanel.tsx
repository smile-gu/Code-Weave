import { useMemo } from 'react'
import { Diff, Hunk, parseDiff } from 'react-diff-view'
import { FileCode2 } from 'lucide-react'
import type { WorkspaceChanges } from '../../shared/api/types'

/** 去除 Git 写入 Diff 正文之前的诊断信息，兼容已经产生的历史 Run。 */
export function normalizeUnifiedDiff(unifiedDiff: string) {
  const headerIndex = unifiedDiff.search(/^diff --git /m)
  return headerIndex > 0 ? unifiedDiff.slice(headerIndex) : unifiedDiff
}

/** 使用成熟 Unified Diff 组件展示本次 Run 的代码修改。 */
export function DiffPanel({ changes }: { changes: WorkspaceChanges }) {
  const parsed = useMemo(() => {
    const normalizedDiff = normalizeUnifiedDiff(changes.unifiedDiff)
    if (!/^diff --git /m.test(normalizedDiff)) {
      return { files: [], failed: true }
    }
    try {
      return { files: parseDiff(normalizedDiff), failed: false }
    } catch {
      return { files: [], failed: true }
    }
  }, [changes.unifiedDiff])

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
        {parsed.failed && (
          <div className="empty-state error-text">
            代码变更已经采集完成，但 Diff 内容暂时无法解析。请查看变更文件列表或服务日志。
          </div>
        )}
        {!parsed.failed && parsed.files.map((file, index) => (
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
