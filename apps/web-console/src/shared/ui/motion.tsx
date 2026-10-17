// Mount-only adaptation of React Bits AnimatedContent, revision 310fc737.
// License: MIT + Commons Clause. See THIRD_PARTY_NOTICES.md.
import { useRef, type ReactNode } from 'react'
import { gsap } from 'gsap'
import { useGSAP } from '@gsap/react'

gsap.registerPlugin(useGSAP)

/** Content starts readable. Animations never own business state or remount tasks. */
export function PageEntrance({ children, className }: { children: ReactNode; className?: string }) {
  const container = useRef<HTMLDivElement>(null)
  useGSAP(() => {
    if (!container.current || !window.matchMedia) return
    const media = gsap.matchMedia()
    media.add('(prefers-reduced-motion: no-preference)', () => {
      const parts = container.current?.querySelectorAll(':scope > [data-entrance]')
      const targets = parts?.length ? parts : container.current
      gsap.fromTo(targets, { y: 10, opacity: .85 }, {
        y: 0, opacity: 1, duration: .32, ease: 'power3.out', stagger: .045,
        overwrite: 'auto', clearProps: 'transform,opacity',
      })
    }, container)
    return () => media.revert()
  }, { scope: container })
  return <div ref={container} className={className}>{children}</div>
}
