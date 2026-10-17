import { fireEvent, render, screen, cleanup } from '@testing-library/react'
import { useEffect } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { gsap } from 'gsap'
import { PageEntrance } from './motion'

afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks() })

describe('PageEntrance', () => {
  it('does not remount content or discard drafts when presentation changes', () => {
    const mount = vi.fn()
    function Draft() { useEffect(mount, []); return <input aria-label="未发送草稿" defaultValue="" /> }
    const view = render(<PageEntrance className="before"><Draft /></PageEntrance>)
    fireEvent.change(screen.getByLabelText('未发送草稿'), { target: { value: '保留这份草稿' } })
    view.rerender(<PageEntrance className="after"><Draft /></PageEntrance>)
    expect(screen.getByLabelText('未发送草稿')).toHaveValue('保留这份草稿')
    expect(mount).toHaveBeenCalledTimes(1)
  })
  it('leaves reduced-motion content readable without creating an entrance tween', () => {
    vi.stubGlobal('matchMedia', vi.fn(query => ({ matches: !query.includes('no-preference'), media: query, addListener: vi.fn(), removeListener: vi.fn(), addEventListener: vi.fn(), removeEventListener: vi.fn() })))
    const tween = vi.spyOn(gsap, 'fromTo')
    const { container } = render(<PageEntrance><p>立即可读</p></PageEntrance>)
    expect(tween).not.toHaveBeenCalled()
    expect(container.firstElementChild).not.toHaveAttribute('style')
    expect(screen.getByText('立即可读')).toBeInTheDocument()
  })
  it('reverts its scoped media context when unmounted', () => {
    vi.stubGlobal('matchMedia', vi.fn(query => ({ matches: false, media: query, addListener: vi.fn(), removeListener: vi.fn(), addEventListener: vi.fn(), removeEventListener: vi.fn() })))
    const media = gsap.matchMedia()
    const revert = vi.spyOn(media, 'revert')
    vi.spyOn(gsap, 'matchMedia').mockReturnValue(media)
    const view = render(<PageEntrance><p>工作区</p></PageEntrance>)
    view.unmount()
    expect(revert).toHaveBeenCalled()
  })
})
