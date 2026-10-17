// Adapted from satnaing/shadcn-admin (MIT), e16c87f.
// See THIRD_PARTY_NOTICES.md. Tailwind classes replaced with local CSS; no runtime added.
import type { ComponentProps, ReactNode } from 'react'

export function Card({ className = '', ...props }: ComponentProps<'div'>) {
  return <div data-slot="card" className={`ui-card ${className}`} {...props} />
}
export function CardHeader({ className = '', ...props }: ComponentProps<'div'>) {
  return <div data-slot="card-header" className={`ui-card-header ${className}`} {...props} />
}
export function CardContent({ className = '', ...props }: ComponentProps<'div'>) {
  return <div data-slot="card-content" className={`ui-card-content ${className}`} {...props} />
}
export function Badge({ className = '', variant = 'secondary', ...props }: ComponentProps<'span'> & { variant?: 'secondary' | 'outline' | 'destructive' }) {
  return <span data-slot="badge" className={`ui-badge badge-${variant} ${className}`} {...props} />
}
export function Skeleton({ className = '', ...props }: ComponentProps<'div'>) {
  return <div data-slot="skeleton" aria-hidden="true" className={`ui-skeleton ${className}`} {...props} />
}

/** Technical data stays available, but is not part of the primary reading flow. */
export function TechnicalDetails({ children, label = '技术详情' }: { children: ReactNode; label?: string }) {
  return <details className="technical-details"><summary>{label}</summary><div>{children}</div></details>
}
