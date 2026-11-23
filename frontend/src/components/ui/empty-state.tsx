import type { LucideIcon } from 'lucide-react'
import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'
import { Card } from './card'

/**
 * DESIGN.md Empty state: a centered 48 px line icon in `{colors.muted-foreground}`, one `{typography.h3}` sentence,
 * at most one `{typography.body-sm}` line, and at most one action. No illustration. It sits in a card so it keeps
 * the place of the list it stands for; `bare` drops the card where it already sits inside one.
 */
export function EmptyState({
  icon: Icon,
  title,
  description,
  action,
  bare = false,
  className,
}: {
  icon: LucideIcon
  title: ReactNode
  description?: ReactNode
  action?: ReactNode
  bare?: boolean
  className?: string
}) {
  const body = (
    <div className={cn('flex flex-col items-center gap-2 px-4 py-10 text-center', className)}>
      <Icon aria-hidden="true" className="size-12 text-muted-foreground" strokeWidth={1.5} />
      <p className="text-h3 text-balance">{title}</p>
      {description && <p className="max-w-reading-max text-body-sm text-muted-foreground">{description}</p>}
      {action && <div className="mt-2">{action}</div>}
    </div>
  )
  return bare ? body : <Card padding="none">{body}</Card>
}
