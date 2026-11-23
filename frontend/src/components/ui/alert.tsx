import { CircleAlert, Info, TriangleAlert, type LucideIcon } from 'lucide-react'
import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'

export type AlertTone = 'info' | 'warning' | 'danger'

const TONES: Record<AlertTone, { box: string; icon: string; Icon: LucideIcon }> = {
  info: { box: 'border-l-info bg-info-soft', icon: 'text-info', Icon: Info },
  warning: { box: 'border-l-warning bg-warning-soft', icon: 'text-warning', Icon: TriangleAlert },
  danger: { box: 'border-l-destructive bg-destructive-soft', icon: 'text-destructive', Icon: CircleAlert },
}

/**
 * DESIGN.md Alert (`alert-info`, `alert-warning`, `alert-danger`): a tinted block with a 4 px left bar in the tone,
 * `{rounded.md}`, a 20 px icon, an optional `{typography.label}` headline, and a `{typography.body-sm}` body. There
 * is no success alert: success is a toast or a done badge. `role` is left to the caller: "status" for a polite
 * announcement, "alert" for an error, none for a standing notice.
 */
export function Alert({
  tone = 'info',
  title,
  icon,
  action,
  role,
  className,
  children,
}: {
  tone?: AlertTone
  title?: ReactNode
  /** Another icon than the tone's, such as a pause or a flask; `null` for none. */
  icon?: LucideIcon | null
  /** One action under the text: a link or a compact button. */
  action?: ReactNode
  role?: 'status' | 'alert'
  className?: string
  children?: ReactNode
}) {
  const tone_ = TONES[tone]
  const Icon = icon === undefined ? tone_.Icon : icon
  return (
    <div role={role} className={cn('flex gap-3 rounded-md border-l-4 p-4 text-foreground', tone_.box, className)}>
      {Icon && <Icon aria-hidden="true" className={cn('mt-0.5 size-5 shrink-0', tone_.icon)} />}
      <div className="flex min-w-0 grow flex-col gap-1 text-body-sm">
        {title && <p className="text-label font-semibold">{title}</p>}
        {children}
        {action && <div className="mt-2 flex flex-wrap items-center gap-2">{action}</div>}
      </div>
    </div>
  )
}
