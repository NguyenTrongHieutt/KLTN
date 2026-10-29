import { TermHint } from '@/components/TermHint'
import type { Histories } from '@/features/reputation/historyApi'
import { HistoryRegion } from '@/features/reputation/RoleHistories'
import { t } from '@/i18n/t'
import type { FitMetrics as Metrics } from './applicationApi'
import { fitRows, profileFacts } from './fitRows'

/**
 * The Fit metrics (AD-17, X12, UX-DR101) on a Bid card and in CUS-12: each metric on its own line with its formula as a
 * Term hint, "Chưa có dữ liệu" when empty, and the Rating as Mentor and the month joined beside them, which wait for
 * the histories in short. No combined score, rank, or "best" mark, and nothing orders the cards by them.
 */
export function FitMetricsList({
  metrics,
  histories,
  username,
  memberSince,
}: {
  metrics: Metrics | undefined
  histories: Histories
  /** The bidder's GitHub username, whose history the Rating comes from; absent for one who unlinked it. */
  username: string | undefined
  memberSince: string | undefined
}) {
  const facts = (
    <ul className="flex flex-wrap gap-1.5">
      {profileFacts(username ? histories.byUsername.get(username) : undefined, memberSince).map((fact) => (
        <li key={fact} className="rounded-full bg-muted px-2 py-0.5 text-caption">
          {fact}
        </li>
      ))}
    </ul>
  )
  return (
    <div className="flex flex-col gap-2">
      <dl className="grid gap-x-4 gap-y-1.5 text-body-sm sm:grid-cols-[minmax(0,14rem)_minmax(0,1fr)]">
        {fitRows(metrics).map((row) => (
          <div key={row.term} className="contents">
            <dt className="text-muted-foreground">
              <TermHint term={row.term} />
            </dt>
            <dd className="break-words">{row.value}</dd>
          </div>
        ))}
      </dl>
      {username ? <HistoryRegion histories={histories}>{facts}</HistoryRegion> : facts}
      <p className="text-caption text-muted-foreground">{t('screen.matching.fit.note')}</p>
    </div>
  )
}
