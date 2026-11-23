import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'

/**
 * The frame of a page (DESIGN.md, Layout & Spacing). Inside the signed-in shell every page starts at the same
 * left edge as the top bar, so nothing jumps sideways between destinations:
 * - `content` fills `{spacing.content-max}`: lists, the Job workspace, tables;
 * - `reading` keeps the same frame but caps its column at `{spacing.reading-max}`, left-aligned: profiles, forms,
 *   notifications;
 * - `focused` centers one reading column, for the focused task routes whose header is centered the same way.
 */
export function Page({
  width = 'content',
  className,
  children,
}: {
  width?: 'content' | 'reading' | 'focused'
  className?: string
  children: ReactNode
}) {
  return (
    <div
      className={cn(
        'mx-auto w-full px-gutter-mobile py-section-gap md:px-gutter-desktop',
        width === 'focused' ? 'max-w-reading-frame' : 'max-w-content-max',
      )}
    >
      <div className={cn('flex min-w-0 flex-col gap-6', width === 'reading' && 'max-w-reading-max', className)}>
        {children}
      </div>
    </div>
  )
}
