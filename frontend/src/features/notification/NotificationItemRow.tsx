import { Bell, BriefcaseBusiness, CircleCheck, Info, Mail, TriangleAlert, Users, type LucideIcon } from 'lucide-react'
import { useNavigate } from 'react-router'
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
 * time; unread items carry a dot and a tinted fill. Tapping marks it read and opens its surface;
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
  const navigate = useNavigate()
  const markRead = useMarkRead()
  const Icon = ICONS[item.icon] ?? Bell
  const body = notificationBody(item)

  function open() {
    if (!item.read && !offline) {
      markRead.mutate(item.id)
    }
    onOpen?.()
    navigate(item.link)
  }

  return (
    <button
      type="button"
      onClick={open}
      className={cn(
        'flex w-full items-start gap-3 rounded-lg p-3 text-left hover:bg-muted',
        !item.read && 'bg-primary-soft',
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
    </button>
  )
}
