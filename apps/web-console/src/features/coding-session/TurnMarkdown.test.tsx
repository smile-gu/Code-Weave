import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { TurnMarkdown } from './TurnMarkdown'

describe('TurnMarkdown', () => {
  it('renders headings, lists, emphasis and fenced code', () => {
    render(<TurnMarkdown content={'## 修改完成\n\n- **新增**接口\n- 保留 `main`\n\n```java\nrecord User() {}\n```'} />)

    expect(screen.getByRole('heading', { name: '修改完成' })).toBeInTheDocument()
    expect(screen.getByText('新增').tagName).toBe('STRONG')
    expect(screen.getByText('main')).toBeInTheDocument()
    expect(screen.getByText('record User() {}')).toBeInTheDocument()
  })

  it('does not render raw HTML from agent output', () => {
    render(<TurnMarkdown content={'正常内容\n\n<img src="x" alt="unsafe">'} />)

    expect(screen.getByText('正常内容')).toBeInTheDocument()
    expect(screen.queryByRole('img', { name: 'unsafe' })).not.toBeInTheDocument()
  })
})
