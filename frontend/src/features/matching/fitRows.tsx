import type { ReactNode } from 'react'
import type { TermKey } from '@/components/TermHint'
import type { FactKind } from '@/features/identity/personApi'
import { formatMonth } from '@/features/identity/personText'
import type { ReputationHistory } from '@/features/reputation/historyApi'
import { t, type MessageKey } from '@/i18n/t'
import type { FitMetrics as Metrics } from './applicationApi'

/** "Chưa có dữ liệu", never a 0 shown as data (UX-DR101). */
function empty(): string {
  return t('screen.matching.fit.empty')
}

function factName(kind: FactKind): string {
  return t(`screen.person.fact.${kind}`)
}

/** One metric's rows in the fixed order: its label with the formula as a Term hint, then its value or "Chưa có dữ liệu". */
export function fitRows(metrics: Metrics | undefined): { term: TermKey; value: ReactNode }[] {
  const jobs = (n: number | undefined) => (n ? t('screen.matching.fit.jobs', { n }) : empty())
  return [
    {
      term: 'fitSkills',
      value:
        metrics && metrics.skillsSought > 0 ? (
          <>
            {t('screen.matching.fit.skills', { k: metrics.sharedSkills.length, n: metrics.skillsSought })}
            {metrics.sharedSkills.length > 0 && (
              <> {t('screen.matching.fit.skillNames', { names: metrics.sharedSkills.join(', ') })}</>
            )}
          </>
        ) : (
          empty()
        ),
    },
    {
      term: 'fitJobTypes',
      value:
        metrics && metrics.jobTypeMatches.length > 0 ? (
          <span className="flex flex-col gap-0.5">
            {metrics.jobTypeMatches.map((match) => (
              <span key={match.jobType}>
                {t('screen.matching.fit.jobType', {
                  n: match.asMentor,
                  m: match.asCrew,
                  type: t(`term.jobType${match.jobType}.label` as MessageKey),
                })}
              </span>
            ))}
          </span>
        ) : (
          empty()
        ),
    },
    { term: 'fitDoneMentor', value: jobs(metrics?.jobsAsMentor) },
    { term: 'fitDoneCrew', value: jobs(metrics?.jobsAsCrew) },
    {
      term: 'fitOnTime',
      value: metrics?.onTime ? t('screen.matching.fit.onTime', { k: metrics.onTime.onTime, n: metrics.onTime.total }) : empty(),
    },
    { term: 'fitActive', value: jobs(metrics?.activeJobsAsMentor) },
    {
      term: 'fitVerified',
      value:
        metrics && metrics.verifications.length > 0
          ? t('screen.matching.fit.verifications', {
              k: metrics.verifications.length,
              names: metrics.verifications.map(factName).join(', '),
            })
          : empty(),
    },
  ]
}

/** The profile facts beside the metrics, never metrics (X12): the Rating as Mentor or "Mới", and the month joined. */
export function profileFacts(history: ReputationHistory | undefined, memberSince: string | undefined): string[] {
  const facts: string[] = []
  const mentor = history?.mentor
  if (mentor && mentor.count > 0 && mentor.average != null) {
    facts.push(
      t('screen.matching.fit.rating', {
        rating: t('screen.matching.fit.ratingValue', {
          average: mentor.average.toLocaleString('vi-VN', { maximumFractionDigits: 1 }),
          count: mentor.count,
        }),
      }),
    )
  } else {
    facts.push(
      t('screen.matching.fit.rating', {
        rating: history?.newcomer ? t('screen.matching.fit.newcomer') : t('screen.matching.fit.noRating'),
      }),
    )
  }
  if (memberSince) {
    facts.push(t('screen.matching.fit.joined', { month: formatMonth(memberSince) }))
  }
  return facts
}

