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
import { formatDate } from '@/lib/dates'
import { Page } from '@/components/ui/page'

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
    <Page width="reading">
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
          // Grouped by day, newest first (2026-11-23): "Hôm nay", "Hôm qua", then the date.
          <div className="flex flex-col gap-4">
            {byDay(items).map(([day, group]) => (
              <section key={day} aria-label={day} className="flex flex-col gap-2">
                <h2 className="text-label text-muted-foreground">{day}</h2>
                <ul className="flex flex-col divide-y divide-border overflow-hidden rounded-lg border border-border bg-card">
                  {group.map((item) => (
                    <li key={item.id}>
                      <NotificationItemRow item={item} offline={offline} />
                    </li>
                  ))}
                </ul>
              </section>
            ))}
          </div>
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
    </Page>
  )
}

/** The notifications in groups of one day each, in Vietnam, labelled "Hôm nay", "Hôm qua", or the date. */
function byDay<T extends { createdAt: string }>(items: T[]): [string, T[]][] {
  const today = formatDate(new Date().toISOString())
  const yesterday = formatDate(new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString())
  const groups = new Map<string, T[]>()
  for (const item of items) {
    const day = formatDate(item.createdAt)
    const label =
      day === today
        ? t('screen.shared.notifications.today')
        : day === yesterday
          ? t('screen.shared.notifications.yesterday')
          : day
    groups.set(label, [...(groups.get(label) ?? []), item])
  }
  return [...groups.entries()]
}
