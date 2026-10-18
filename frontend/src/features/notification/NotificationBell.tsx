import { Bell } from 'lucide-react'
import { useState, useSyncExternalStore } from 'react'
import { Link } from 'react-router'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { Skeleton } from '@/components/Skeleton'
import { t } from '@/i18n/t'
import { useLatestNotifications, useUnreadCount } from './hooks'
import { NotificationItemRow } from './NotificationItemRow'

const DESKTOP = '(min-width: 768px)'

/**
 * The top-bar bell (UX-DR11): the unread count, a popover of the 10 latest from 768 px, and ALL-1
 * itself on phones.
 */
export function NotificationBell({ offline }: { offline: boolean }) {
  const unread = useUnreadCount()
  const desktop = useMatchMedia(DESKTOP)
  const [open, setOpen] = useState(false)
  const count = unread.data ?? 0
  const label = count > 0 ? t('screen.shared.notifications.bellUnread', { count }) : t('screen.shared.notifications.bell')
  const icon = (
    <>
      <Bell aria-hidden="true" className="size-5" />
      {count > 0 && (
        <span
          aria-hidden="true"
          className="absolute -top-0.5 -right-0.5 flex min-w-5 items-center justify-center rounded-full bg-destructive px-1 text-tag text-destructive-foreground"
        >
          {count > 99 ? '99+' : count}
        </span>
      )}
    </>
  )
  const trigger = 'relative flex size-10 items-center justify-center rounded-full text-foreground hover:bg-muted'

  if (!desktop) {
    return (
      <Link to="/thong-bao" aria-label={label} className={trigger}>
        {icon}
      </Link>
    )
  }
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger aria-label={label} className={trigger}>
        {icon}
      </PopoverTrigger>
      <PopoverContent align="end" className="w-96 max-w-[calc(100vw-32px)] p-2">
        <BellList offline={offline} onOpen={() => setOpen(false)} />
      </PopoverContent>
    </Popover>
  )
}

function BellList({ offline, onOpen }: { offline: boolean; onOpen: () => void }) {
  const latest = useLatestNotifications(true)
  const items = latest.data?.items ?? []
  return (
    <div className="flex flex-col gap-1">
      <p className="px-2 pt-1 text-label">{t('screen.shared.notifications.latest')}</p>
      {latest.isPending ? (
        <div className="flex flex-col gap-2 p-2">
          <Skeleton className="h-12 w-full" />
          <Skeleton className="h-12 w-full" />
        </div>
      ) : items.length === 0 ? (
        <p className="px-2 py-4 text-body-sm text-muted-foreground">{t('screen.shared.notifications.empty')}</p>
      ) : (
        <ul className="flex max-h-[60vh] flex-col gap-1 overflow-y-auto">
          {items.map((item) => (
            <li key={item.id}>
              <NotificationItemRow item={item} offline={offline} onOpen={onOpen} />
            </li>
          ))}
        </ul>
      )}
      <Link
        to="/thong-bao"
        onClick={onOpen}
        className="rounded-md px-2 py-2 text-center text-body-sm text-primary underline-offset-4 hover:underline"
      >
        {t('screen.shared.notifications.seeAll')}
      </Link>
    </div>
  )
}

function useMatchMedia(query: string): boolean {
  return useSyncExternalStore(
    (notify) => {
      const list = window.matchMedia(query)
      list.addEventListener('change', notify)
      return () => list.removeEventListener('change', notify)
    },
    () => window.matchMedia(query).matches,
    () => false,
  )
}
