import { Star } from 'lucide-react'
import { formatMonth } from '@/features/identity/personText'
import { t, type MessageKey } from '@/i18n/t'
import type { PortfolioEntry } from './historyApi'

/**
 * A profile's visible Rating summary in one role (AD-33): "{avg} sao ({n} lượt từ …)", "Mới" for a Mentor with no
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
  /** Who gave the Ratings: Customers rate a Mentor, Mentors rate a Crew member. */
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

/**
 * "Hồ sơ năng lực" (FR-50): entries the platform makes from real Jobs, or an empty line while there are
 * none. Each shows its facts from records, the Rating once visible, the summary the Customer approved,
 * and the Product name only if allowed.
 */
export function PortfolioSection({
  entries,
  headingId,
  level = 2,
}: {
  entries: PortfolioEntry[]
  headingId: string
  /** The heading's level: 2 on its own, 3 inside a history. */
  level?: 2 | 3
}) {
  const Heading = level === 2 ? 'h2' : 'h3'
  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-2">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <Heading id={headingId} className={level === 2 ? 'text-h3' : 'text-label font-semibold'}>
          {t('screen.team.reputation.portfolio')}
        </Heading>
        <span className="text-caption text-muted-foreground">{t('screen.team.reputation.portfolioNote')}</span>
      </div>
      {entries.length === 0 ? (
        <p className="text-body-sm text-muted-foreground">{t('screen.team.reputation.portfolioEmpty')}</p>
      ) : (
        <ul className="grid gap-3 md:grid-cols-2">
          {entries.map((entry) => (
            <li key={`${entry.title}-${entry.closedAt}`} className="flex flex-col gap-1 rounded-lg border border-border bg-card p-4">
              <p className="text-label font-semibold break-words">
                {entry.title || t('screen.team.reputation.portfolioFacts.untitled')}
              </p>
              {entry.productName && <p className="text-caption break-words">{entry.productName}</p>}
              <p className="text-caption text-muted-foreground">
                {[
                  entry.jobTypes.map((type) => t(`term.jobType${type}.label` as MessageKey)).join(' · '),
                  formatMonth(entry.closedAt),
                  t('screen.team.reputation.milestones', { n: entry.milestones }),
                ].join(' · ')}
              </p>
              <EntryFacts entry={entry} />
              {entry.skills.length > 0 && (
                <p className="text-caption text-muted-foreground break-words">{entry.skills.join(' · ')}</p>
              )}
              {entry.summary && <p className="text-body-sm break-words whitespace-pre-line">{entry.summary}</p>}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function EntryFacts({ entry }: { entry: PortfolioEntry }) {
  const facts =
    entry.role === 'MENTOR'
      ? [
          t('screen.team.reputation.portfolioFacts.milestonesHandedOver', { n: entry.milestonesHandedOver ?? 0 }),
          t('screen.team.reputation.portfolioFacts.reviewsWritten', { n: entry.reviewsWritten ?? 0 }),
        ]
      : [t('screen.team.reputation.portfolioFacts.workItemsApproved', { n: entry.workItemsApproved ?? 0 })]
  return (
    <p className="flex flex-wrap items-center gap-1.5 text-caption">
      <span>{facts.join(' · ')}</span>
      {entry.rating != null && (
        <span className="flex items-center gap-1">
          <Star aria-hidden="true" className="size-3.5 fill-warning text-warning" />
          {t('screen.team.reputation.portfolioFacts.rating', { n: entry.rating })}
        </span>
      )}
    </p>
  )
}
