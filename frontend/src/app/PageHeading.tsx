import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'

/**
 * A page's `h1`. It takes programmatic focus after a route change (Accessibility Floor), so it is
 * focusable by script but not in the tab order.
 *
 * `inNavigation` is for the four destinations of the shell (Trang chủ, Công việc, Tìm việc, Sản phẩm): the sidebar,
 * its rail and the tab bar each show that name selected at every width, so the page does not print it a second time
 * above its content (the layout of 2026-11-24). The `h1` stays for a screen reader and for the focus after a route
 * change. A page reached another way (the bell, the account menu, a link) keeps its visible heading.
 */
export function PageHeading({
  children,
  className,
  inNavigation = false,
}: {
  children: ReactNode
  className?: string
  inNavigation?: boolean
}) {
  return (
    <h1 tabIndex={-1} className={cn(inNavigation ? 'sr-only' : 'text-h1 focus:outline-none', className)}>
      {children}
    </h1>
  )
}
