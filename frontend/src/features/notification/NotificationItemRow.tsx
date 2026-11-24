import { Bell, BriefcaseBusiness, ChevronRight, CircleCheck, Info, Mail, TriangleAlert, Users, type LucideIcon } from 'lucide-react'
import { Link } from 'react-router'
import { t } from '@/i18n/t'
import { formatDayMonthTime } from '@/lib/dates'
import { cn } from '@/lib/utils'
import type { NotificationItem } from './api'
import { useMarkRead } from './hooks'
import { notificationBody, notificationTitle } from './texts'

/** The type icons the registry names (`NotificationType.icon`); an unknown one is the bell. */
const ICONS: Record<string, LucideIcon> = {
  bell: Bell,
  info: Info,
  mail: Mail,
  alert: TriangleAlert,
  briefcase: BriefcaseBusiness,
  check: CircleCheck,
  users: Users,
}

/** The circle behind a type's icon: a warning for alerts, done for confirmations, the brand tint for the rest. */
const TONES: Record<string, string> = {
  bell: 'bg-primary-soft text-primary',
  alert: 'bg-warning-soft text-warning',
  check: 'bg-success-soft text-success',
}

/**
 * A notification list item (DESIGN.md, UX-DR16): the type icon in a circle, the title, the body line, and the
 * time; unread items carry a dot, a bolder title and a tinted fill, and a chevron says the row opens. It is a link to its surface, so
 * it opens in a new tab too; following it marks it read;
 * offline it only opens it, because commands wait for the connection (UX-DR26).
 */
export function NotificationItemRow({
  item,
  offline,
  onOpen,
}: {
  item: NotificationItem
  offline: boolean
  onOpen?: () => void
}) {
  const markRead = useMarkRead()
  const Icon = ICONS[item.icon] ?? Bell
  const body = notificationBody(item)

  function open() {
    if (!item.read && !offline) {
      markRead.mutate(item.id)
    }
    onOpen?.()
  }

  return (
    <Link
      to={item.link}
      onClick={open}
      className={cn(
        'flex w-full items-start gap-3 px-4 py-3 text-left text-inherit no-underline hover:bg-muted',
        !item.read && 'bg-primary-soft/50',
      )}
    >
      <span aria-hidden="true" className={cn('flex size-10 shrink-0 items-center justify-center rounded-full', TONES[item.icon] ?? TONES.bell)}>
        <Icon className="size-5" />
      </span>
      <span className="flex min-w-0 grow flex-col gap-0.5">
        <span className={cn('text-label', !item.read && 'font-semibold')}>{notificationTitle(item)}</span>
        {body && <span className="text-body-sm text-muted-foreground">{body}</span>}
        <span className="text-caption text-muted-foreground">{formatDayMonthTime(item.createdAt)}</span>
      </span>
      {!item.read && (
        <span className="mt-4 size-2.5 shrink-0 rounded-full bg-primary">
          <span className="sr-only">{t('screen.shared.notifications.unread')}</span>
        </span>
      )}
      <ChevronRight aria-hidden="true" className="mt-3 size-4 shrink-0 text-muted-foreground" />
    </Link>
  )
}
