import { Star } from 'lucide-react'
import { t, type MessageKey } from '@/i18n/t'
import type { PortfolioEntry } from './teamApi'
import { formatMonth } from './teamText'

/**
 * A profile's visible Rating summary (AD-33): "{avg} sao ({n} lượt từ …)", "Mới" for a Mentor with no
 * Job closed with Accepted work, or "Chưa có lượt chấm sao".
 */
export function RatingLine({
  average,
  count,
  from,
  newcomer = false,
}: {
  average?: number
  count: number
  /** Who gave the Ratings: Customers rate Mentors, Mentors rate Students. */
  from: 'customers' | 'mentors'
  newcomer?: boolean
}) {
  if (count > 0 && average != null) {
    return (
      <p className="flex flex-wrap items-center gap-1.5 text-body-sm">
        <Star aria-hidden="true" className="size-4 fill-warning text-warning" />
        <span className="font-semibold">
          {t('screen.team.reputation.average', { average: average.toLocaleString('vi-VN', { maximumFractionDigits: 1 }) })}
        </span>
        <span className="text-muted-foreground">
          {t(from === 'customers' ? 'screen.team.reputation.fromCustomers' : 'screen.team.reputation.fromMentors', {
            n: count,
          })}
        </span>
      </p>
    )
  }
  if (newcomer) {
    return (
      <span className="w-fit rounded-full bg-primary-soft px-2 py-0.5 text-caption font-semibold text-primary">
        {t('screen.team.reputation.new')}
      </span>
    )
  }
  return <p className="text-body-sm text-muted-foreground">{t('screen.team.reputation.none')}</p>
}

/** "Hồ sơ năng lực" (FR-50): entries the platform makes from real Jobs, or an empty line while there are none. */
export function PortfolioSection({ entries, headingId }: { entries: PortfolioEntry[]; headingId: string }) {
  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-2">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 id={headingId} className="text-h3">
          {t('screen.team.reputation.portfolio')}
        </h2>
        <span className="text-caption text-muted-foreground">{t('screen.team.reputation.portfolioNote')}</span>
      </div>
      {entries.length === 0 ? (
        <p className="text-body-sm text-muted-foreground">{t('screen.team.reputation.portfolioEmpty')}</p>
      ) : (
        <ul className="grid gap-3 md:grid-cols-2">
          {entries.map((entry) => (
            <li key={`${entry.title}-${entry.closedAt}`} className="flex flex-col gap-1 rounded-lg border border-border bg-card p-4">
              <p className="text-label font-semibold break-words">{entry.title}</p>
              <p className="text-caption text-muted-foreground">
                {[
                  entry.jobTypes.map((type) => t(`term.jobType${type}.label` as MessageKey)).join(' · '),
                  formatMonth(entry.closedAt),
                  t('screen.team.reputation.milestones', { n: entry.milestones }),
                ].join(' · ')}
              </p>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
