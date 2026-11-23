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

/**
 * A notification list item (DESIGN.md, UX-DR16): the type icon, the title, the body line, and the
 * time; unread items carry a dot and a tinted fill, and a chevron says the row opens. It is a link to its surface, so
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
      <Icon aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-muted-foreground" />
      <span className="flex min-w-0 grow flex-col gap-0.5">
        <span className="text-label">{notificationTitle(item)}</span>
        {body && <span className="text-body-sm text-muted-foreground">{body}</span>}
        <span className="text-caption text-muted-foreground">{formatDayMonthTime(item.createdAt)}</span>
      </span>
      {!item.read && (
        <span className="mt-2 size-2 shrink-0 rounded-full bg-primary">
          <span className="sr-only">{t('screen.shared.notifications.unread')}</span>
        </span>
      )}
      <ChevronRight aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
    </Link>
  )
}
