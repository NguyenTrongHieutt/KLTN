import { Bell } from 'lucide-react'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { usePollHealth } from '@/app/usePollHealth'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { useMarkAllRead, useNotifications, useUnreadCount } from './hooks'
import { NotificationItemRow } from './NotificationItemRow'

/**
 * ALL-1 (`/thong-bao`): the notifications of the last 90 days, newest first, with "Đánh dấu đã đọc
 * tất cả" (FR-48, UX-DR16).
 */
export function NotificationsPage() {
  usePageTitle(t('screen.shared.notifications.title'))
  const notifications = useNotifications()
  const unread = useUnreadCount()
  const markAllRead = useMarkAllRead()
  const { offline } = usePollHealth()
  const items = notifications.data?.pages.flatMap((page) => page.items) ?? []

  return (
    <div className="mx-auto flex max-w-reading-max flex-col gap-4 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <PageHeading>{t('screen.shared.notifications.heading')}</PageHeading>
        <Button
          variant="secondary"
          size="compact"
          disabled={offline || markAllRead.isPending || (unread.data ?? 0) === 0}
          onClick={() => markAllRead.mutate()}
        >
          {t('screen.shared.notifications.markAllRead')}
        </Button>
      </div>
      <RegionState
        query={notifications}
        skeleton={
          <div className="flex flex-col gap-2">
            {[0, 1, 2, 3].map((row) => (
              <Skeleton key={row} className="h-16 w-full" />
            ))}
          </div>
        }
      >
        {items.length > 0 ? (
          <ul className="flex flex-col gap-1 rounded-lg border border-border bg-card p-1">
            {items.map((item) => (
              <li key={item.id}>
                <NotificationItemRow item={item} offline={offline} />
              </li>
            ))}
          </ul>
        ) : (
          <div className="flex flex-col items-center gap-3 rounded-lg border border-border bg-card px-6 py-10 text-center">
            <Bell aria-hidden="true" className="size-12 text-muted-foreground" />
            <p className="text-h3">{t('screen.shared.notifications.empty')}</p>
          </div>
        )}
        {notifications.hasNextPage && (
          <Button
            variant="secondary"
            className="self-center"
            disabled={notifications.isFetchingNextPage}
            onClick={() => notifications.fetchNextPage()}
          >
            {t('screen.shared.notifications.more')}
          </Button>
        )}
      </RegionState>
    </div>
  )
}
