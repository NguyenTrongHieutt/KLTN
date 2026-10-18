import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'

/**
 * A page's `h1`. It takes programmatic focus after a route change (Accessibility Floor), so it is
 * focusable by script but not in the tab order.
 */
export function PageHeading({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <h1 tabIndex={-1} className={cn('text-h1 focus:outline-none', className)}>
      {children}
    </h1>
  )
}
