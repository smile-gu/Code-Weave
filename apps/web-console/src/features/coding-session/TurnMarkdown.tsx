import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

type TurnMarkdownProps = {
  /** Claude 返回的 Markdown 文本。 */
  content: string
}

/** 安全渲染 Claude 的 Markdown 输出，不开启任意 HTML 注入。 */
export function TurnMarkdown({ content }: TurnMarkdownProps) {
  return (
    <div className="turn-markdown">
      <ReactMarkdown remarkPlugins={[remarkGfm]}>{content}</ReactMarkdown>
    </div>
  )
}
