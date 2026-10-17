import { Terminal } from 'lucide-react'
import type { ProcessOutput } from '../../../features/run-monitor/activityProjection'

/** 在主活动时间线之外展示 Maven 原始进程输出。 */
export function ProcessConsole({ output }: { output: ProcessOutput }) {
  if (output.chunks.length === 0) return null

  return (
    <details className="process-console">
      <summary>
        <span><Terminal size={14} /> Maven 实时终端</span>
        <small>{output.chunks.length} 个输出片段</small>
      </summary>
      <div className="console-output">
        <pre>
          {output.chunks.map((chunk) => (
            <span className={chunk.stream === 'stderr' ? 'console-stderr' : undefined} key={chunk.sequence}>
              {chunk.text}
            </span>
          ))}
        </pre>
      </div>
    </details>
  )
}
