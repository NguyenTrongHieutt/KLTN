import { useId } from 'react'
import { t, type MessageKey } from '@/i18n/t'
import type { PersonReputation, RoleReputation, RoleSummary } from './historyApi'
import { PortfolioSection, RatingLine } from './ReputationParts'

/**
 * PUB-4's two histories (UX-DR96, R17): "Khi làm người hướng dẫn" and "Khi làm thành viên nhóm", each with its Jobs
 * done by Job Type, that role's visible Ratings, and its Portfolio Entries; a role never held reads "Chưa có". Nothing
 * here names another Job's Customer or co-members, and a Product only where its Customer allowed it.
 */
export function RoleHistories({ reputation }: { reputation: PersonReputation }) {
  return (
    <div className="flex flex-col gap-4">
      <RoleHistory role="mentor" history={reputation.mentor} newcomer={reputation.newcomer} />
      <RoleHistory role="crew" history={reputation.crew} newcomer={false} />
    </div>
  )
}

function RoleHistory({ role, history, newcomer }: { role: 'mentor' | 'crew'; history: RoleReputation; newcomer: boolean }) {
  const id = useId()
  const held = history.jobs > 0 || history.count > 0 || history.portfolio.length > 0
  return (
    <section aria-labelledby={id} className="flex flex-col gap-3 rounded-lg border border-border bg-card p-4">
      <h2 id={id} className="text-h3">
        {t(role === 'mentor' ? 'screen.person.asMentor' : 'screen.person.asCrew')}
      </h2>
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
          <PortfolioSection entries={history.portfolio} headingId={`${id}-portfolio`} level={3} />
        </>
      ) : (
        <p className="text-body-sm text-muted-foreground">{t('screen.person.never')}</p>
      )}
    </section>
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
