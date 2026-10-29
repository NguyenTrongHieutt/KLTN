import { useId, type ReactNode } from 'react'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { t, type MessageKey } from '@/i18n/t'
import { below, type HeadingLevel } from './headingLevel'
import type { Histories, PersonReputation, RoleReputation, RoleSummary } from './historyApi'
import { LevelHeading } from './LevelHeading'
import { PortfolioSection, RatingLine } from './ReputationParts'

/**
 * PUB-4's two histories (UX-DR96, R17): "Khi làm người hướng dẫn" and "Khi làm thành viên nhóm", each with its Jobs
 * done by Job Type, that role's visible Ratings, and its Portfolio Entries; a role never held reads "Chưa có". Nothing
 * here names another Job's Customer or co-members, and a Product only where its Customer allowed it.
 */
export function RoleHistories({ reputation, level = 2 }: { reputation: PersonReputation; level?: HeadingLevel }) {
  return (
    <div className="flex flex-col gap-4">
      <RoleHistory role="mentor" history={reputation.mentor} newcomer={reputation.newcomer} level={level} />
      <RoleHistory role="crew" history={reputation.crew} newcomer={false} level={level} />
    </div>
  )
}

function RoleHistory({
  role,
  history,
  newcomer,
  level,
}: {
  role: 'mentor' | 'crew'
  history: RoleReputation
  newcomer: boolean
  level: HeadingLevel
}) {
  const id = useId()
  const held = history.jobs > 0 || history.count > 0 || history.portfolio.length > 0
  return (
    <section aria-labelledby={id} className="flex flex-col gap-3 rounded-lg border border-border bg-card p-4">
      <LevelHeading level={level} id={id} className="text-h3">
        {t(role === 'mentor' ? 'screen.person.asMentor' : 'screen.person.asCrew')}
      </LevelHeading>
      {held ? (
        <>
          <div className="flex flex-col gap-1">
            <p className="text-body-sm font-semibold">{t('screen.person.jobsDone', { n: history.jobs })}</p>
            {history.jobTypes.length > 0 && (
              <p className="text-caption text-muted-foreground">
                {t('screen.person.jobsByType')}:{' '}
                {history.jobTypes
                  .map((type) => `${t(`term.jobType${type.jobType}.label` as MessageKey)} ${type.count}`)
                  .join(' · ')}
              </p>
            )}
          </div>
          <RatingLine
            average={history.average}
            count={history.count}
            from={role === 'mentor' ? 'customers' : 'mentors'}
            newcomer={role === 'mentor' && newcomer}
          />
          <PortfolioSection entries={history.portfolio} headingId={`${id}-portfolio`} level={below(level)} />
        </>
      ) : (
        <p className="text-body-sm text-muted-foreground">{t('screen.person.never')}</p>
      )}
    </section>
  )
}

/**
 * What depends on the Bid cards' histories in short: a Skeleton while they load and the region's error with Retry
 * when they fail, never "Chưa có" or no Rating in their place (UX-DR26).
 */
export function HistoryRegion({ histories, children }: { histories: Histories; children: ReactNode }) {
  return (
    <RegionState query={histories} skeleton={<Skeleton className="h-10 w-full" />}>
      {children}
    </RegionState>
  )
}

/** One role's history in short, for a Bid card (UX-DR100): Jobs done, the Rating, and the Portfolio count. */
export function RoleSummaryLine({ role, summary, newcomer }: { role: 'mentor' | 'crew'; summary?: RoleSummary; newcomer: boolean }) {
  const held = summary && (summary.jobs > 0 || summary.count > 0 || summary.portfolio > 0)
  return (
    <div className="flex flex-col gap-0.5">
      <p className="text-caption font-semibold">
        {t(role === 'mentor' ? 'screen.person.asMentor' : 'screen.person.asCrew')}
      </p>
      {held ? (
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-body-sm">
          <span>
            {t('screen.matching.bid.roleLine', {
              jobs: summary.jobs,
              portfolio: t('screen.person.portfolioCount', { n: summary.portfolio }),
            })}
          </span>
          <RatingLine
            average={summary.average}
            count={summary.count}
            from={role === 'mentor' ? 'customers' : 'mentors'}
            newcomer={role === 'mentor' && newcomer}
          />
        </div>
      ) : (
        <p className="text-body-sm text-muted-foreground">{t('screen.person.never')}</p>
      )}
    </div>
  )
}
