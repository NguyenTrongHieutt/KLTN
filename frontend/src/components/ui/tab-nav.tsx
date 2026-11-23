import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Link } from 'react-router'
import { cn } from '@/lib/utils'

export interface TabNavItem {
  key: string
  to: string
  label: ReactNode
  /** Something new waits there: a 6 px `{colors.primary}` dot, with this text for screen readers. */
  dot?: string
}

/**
 * A row of tabs that are routes (DESIGN.md, Job tabs: the underline variant, the current tab underlined in
 * `{colors.primary}`). When the row is wider than the screen it scrolls sideways with no visible scroll bar, and
 * the edge that hides tabs fades; the current tab is scrolled into view.
 */
export function TabNav({ label, items, current, className }: { label: string; items: TabNavItem[]; current: string; className?: string }) {
  const ref = useRef<HTMLDivElement>(null)
  const [fade, setFade] = useState<{ start: boolean; end: boolean }>({ start: false, end: false })

  useEffect(() => {
    const row = ref.current
    if (!row) {
      return undefined
    }
    const update = () =>
      setFade({ start: row.scrollLeft > 1, end: row.scrollLeft + row.clientWidth < row.scrollWidth - 1 })
    row.querySelector<HTMLElement>('[aria-current="page"]')?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' })
    update()
    row.addEventListener('scroll', update, { passive: true })
    const observer = typeof ResizeObserver === 'undefined' ? undefined : new ResizeObserver(update)
    observer?.observe(row)
    return () => {
      row.removeEventListener('scroll', update)
      observer?.disconnect()
    }
  }, [current, items.length])

  return (
    <nav aria-label={label} className={cn('relative border-b border-border', className)}>
      <div
        ref={ref}
        className={cn(
          'flex gap-1 overflow-x-auto overflow-y-hidden [scrollbar-width:none] [&::-webkit-scrollbar]:hidden',
          fade.end && !fade.start && '[mask-image:linear-gradient(to_right,black_calc(100%-40px),transparent)]',
          fade.start && !fade.end && '[mask-image:linear-gradient(to_left,black_calc(100%-40px),transparent)]',
          fade.start &&
            fade.end &&
            '[mask-image:linear-gradient(to_right,transparent,black_40px,black_calc(100%-40px),transparent)]',
        )}
      >
        {items.map((item) => {
          const active = item.key === current
          return (
            <Link
              key={item.key}
              to={item.to}
              aria-current={active ? 'page' : undefined}
              className={cn(
                'relative flex min-h-tap-min shrink-0 items-center gap-1.5 rounded-t-md px-3 text-label whitespace-nowrap no-underline transition-colors focus-visible:-outline-offset-2',
                'after:absolute after:inset-x-2 after:bottom-0 after:h-0.5 after:rounded-full',
                active
                  ? 'font-semibold text-primary after:bg-primary'
                  : 'text-muted-foreground after:bg-transparent hover:bg-muted hover:text-foreground',
              )}
            >
              {item.label}
              {item.dot && (
                <span className="size-1.5 rounded-full bg-primary">
                  <span className="sr-only">{item.dot}</span>
                </span>
              )}
            </Link>
          )
        })}
      </div>
    </nav>
  )
}
