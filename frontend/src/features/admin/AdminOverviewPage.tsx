import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import type { NotificationItem } from '@/features/notification/api'
import { notificationTitle } from '@/features/notification/texts'
import { t, type MessageKey } from '@/i18n/t'
import { formatDayMonth, formatDayMonthTime, formatTime } from '@/lib/dates'
import { cn } from '@/lib/utils'
import { useAdminAlerts, useAdminOverview } from './adminApi'

/** The alert badge of a notification type; anything not listed reads "Thông tin". */
const CATEGORY: Record<string, { label: MessageKey; tone: string }> = {
  'identity.admin_sign_in_locked': { label: 'screen.admin.overview.security', tone: 'bg-destructive-soft text-destructive' },
}

const INFO = { label: 'screen.admin.overview.info' as MessageKey, tone: 'bg-muted text-muted-foreground' }

/**
 * ADM-1 (`/quan-tri`, UX-DR13, UX-DR69): the queues that exist so far with their oldest item, and
 * the alert list, both refreshed every 60 s. Later stories add their queue tiles.
 */
export function AdminOverviewPage() {
  usePageTitle(t('screen.admin.overview.title'))
  const overview = useAdminOverview()
  const alerts = useAdminAlerts()
  const failed = overview.data?.failedOperations
  const stalled = overview.data?.stalledEvents

  return (
    <div className="flex flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <div className="flex flex-wrap items-baseline justify-between gap-3">
        <PageHeading>{t('screen.admin.overview.heading')}</PageHeading>
        {overview.dataUpdatedAt > 0 && (
          <p className="text-caption text-muted-foreground">
            {t('screen.admin.overview.updatedAt', { time: formatTime(new Date(overview.dataUpdatedAt).toISOString()) })}
          </p>
        )}
      </div>
      <RegionState query={overview} skeleton={<Skeleton className="h-24 w-full max-w-xs" />}>
        <section className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3" aria-label={t('screen.admin.overview.heading')}>
          {failed && (
            <div className="flex flex-col gap-1 rounded-lg border border-border bg-card p-4">
              <p className="text-caption text-muted-foreground">{t('screen.admin.overview.failedOperations')}</p>
              <p className={cn('text-h1', failed.count > 0 && 'text-destructive')}>{failed.count}</p>
              <p className="text-caption text-muted-foreground">
                {failed.oldestAt
                  ? t('screen.admin.overview.oldest', { date: formatDayMonth(failed.oldestAt) })
                  : t('screen.admin.overview.empty')}
              </p>
            </div>
          )}
        </section>
      </RegionState>
      <section aria-labelledby="alerts-heading" className="flex flex-col gap-3">
        <h2 id="alerts-heading" className="text-h2">
          {t('screen.admin.overview.alerts')}
        </h2>
        <RegionState query={alerts} skeleton={<Skeleton className="h-32 w-full" />}>
          <AlertList items={alerts.data?.items ?? []} stalled={stalled} />
        </RegionState>
      </section>
    </div>
  )
}

function AlertList({ items, stalled }: { items: NotificationItem[]; stalled?: { count: number; oldestAt?: string } }) {
  const system = stalled && stalled.count > 0
  if (!system && items.length === 0) {
    return <p className="text-body-sm text-muted-foreground">{t('screen.admin.overview.noAlerts')}</p>
  }
  return (
    <ul className="divide-y divide-border rounded-lg border border-border bg-card text-body-sm">
      {system && (
        <li className="flex flex-wrap items-center gap-3 px-4 py-3">
          <span className="rounded-full bg-warning-soft px-2 py-0.5 text-caption font-semibold text-warning">
            {t('screen.admin.overview.system')}
          </span>
          <p className="min-w-0 grow">{t('screen.admin.overview.stalledEvents', { n: stalled.count })}</p>
          {stalled.oldestAt && <span className="text-caption text-muted-foreground">{formatDayMonthTime(stalled.oldestAt)}</span>}
        </li>
      )}
      {items.map((item) => {
        const category = CATEGORY[item.type] ?? INFO
        return (
          <li key={item.id} className="flex flex-wrap items-center gap-3 px-4 py-3">
            <span className={cn('rounded-full px-2 py-0.5 text-caption font-semibold', category.tone)}>{t(category.label)}</span>
            <p className="min-w-0 grow">{notificationTitle(item)}</p>
            <span className="text-caption text-muted-foreground">{formatDayMonthTime(item.createdAt)}</span>
          </li>
        )
      })}
    </ul>
  )
}
