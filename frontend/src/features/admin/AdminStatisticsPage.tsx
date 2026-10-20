import { useQuery } from '@tanstack/react-query'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { t, type MessageKey } from '@/i18n/t'
import { apiClient, unwrap } from '@/lib/apiClient'

/** The key's label in `vi.json`; a key without one (a new Job Type, say) shows as it is. */
function keyLabel(group: string, key: string): string {
  const path = `screen.admin.statistics.key.${group}.${key}`
  const label = t(path as MessageKey)
  return label === path ? key : label
}

/**
 * ADM-9 (`/quan-tri/thong-ke`, FR-62 counts only, FR-35, SM-C2, UX-DR2, UX-DR46): each group of figures as
 * a list of rows with a plain bar, so it reads as cards on a phone; no chart library and no names. The
 * private-feedback figure appears only once it covers at least 5 feedbacks across 3 Mentors.
 */
export function AdminStatisticsPage() {
  usePageTitle(t('screen.admin.statistics.title'))
  const statistics = useQuery({
    queryKey: ['admin', 'statistics'],
    queryFn: () => unwrap(apiClient.GET('/api/v1/admin/statistics')),
    retry: false,
  })
  return (
    <div className="flex flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <PageHeading>{t('screen.admin.statistics.heading')}</PageHeading>
      <RegionState query={statistics} skeleton={<Skeleton className="h-96 w-full" />}>
        {statistics.data && (
          <div className="grid gap-4 md:grid-cols-2">
            {statistics.data.groups.map((group) => {
              const max = Math.max(1, ...group.counts.map((count) => count.value))
              const feedback = group.group === 'privateFeedback'
              return (
                <section
                  key={group.group}
                  aria-labelledby={`group-${group.group}`}
                  className="flex flex-col gap-2 rounded-lg border border-border bg-card p-4 text-body-sm"
                >
                  <h2 id={`group-${group.group}`} className="text-h3">
                    {t(`screen.admin.statistics.group.${group.group}` as MessageKey)}
                  </h2>
                  {group.counts.length === 0 ? (
                    <p className="text-muted-foreground">
                      {t(feedback ? 'screen.admin.statistics.feedbackHidden' : 'screen.admin.statistics.none')}
                    </p>
                  ) : (
                    <dl className="flex flex-col gap-2">
                      {group.counts.map((count) => {
                        const value =
                          feedback && count.key === 'AVERAGE_TENTHS'
                            ? (count.value / 10).toLocaleString('vi-VN')
                            : count.value.toLocaleString('vi-VN')
                        return (
                          <div key={count.key} className="flex flex-col gap-1">
                            <div className="flex items-baseline justify-between gap-3">
                              <dt>{keyLabel(group.group, count.key)}</dt>
                              <dd className="font-semibold tabular-nums">{value}</dd>
                            </div>
                            {!feedback && (
                              <div aria-hidden="true" className="h-1.5 w-full overflow-hidden rounded-full bg-muted">
                                <div className="h-full rounded-full bg-primary" style={{ width: `${Math.round((count.value / max) * 100)}%` }} />
                              </div>
                            )}
                          </div>
                        )
                      })}
                    </dl>
                  )}
                </section>
              )
            })}
          </div>
        )}
      </RegionState>
    </div>
  )
}
