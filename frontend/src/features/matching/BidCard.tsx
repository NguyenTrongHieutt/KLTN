import { useId } from 'react'
import { Sheet } from '@/components/Sheet'
import { TestTag } from '@/components/TestTag'
import { Button } from '@/components/ui/button'
import { PersonProfileContent } from '@/features/identity/PersonProfile'
import { experienceText } from '@/features/identity/personText'
import { VerifiedFactLines } from '@/features/identity/VerifiedFacts'
import type { ReputationHistory } from '@/features/reputation/historyApi'
import { RoleSummaryLine } from '@/features/reputation/RoleHistories'
import { t } from '@/i18n/t'
import { formatDayMonth } from '@/lib/dates'
import { formatVnd } from '@/lib/money'
import { cn } from '@/lib/utils'
import type { JobApplication } from './applicationApi'
import { FitMetricsList } from './FitMetrics'
import { ProofList } from './ProofList'

/** "{n} bằng chứng · {domain}": the proof count with the first item's domain, or the empty line (UX-DR100). */
function proofSummary(application: JobApplication): string {
  const proof = application.proof
  if (proof.length === 0) {
    return t('screen.matching.proof.none')
  }
  const first = proof[0]
  return first.kind === 'LINK' && first.domain
    ? t('screen.matching.bid.proofFirst', { n: proof.length, domain: first.domain })
    : t('screen.matching.bid.proofCount', { n: proof.length })
}

/**
 * A Bid card (UX-DR100) in its fixed order: the bidder's Verified facts; what they posted (price, timeline, an approach
 * excerpt, and the proof count); their history as Mentor and as Crew member; then the Fit metrics. "Đã mời" is a chip
 * that changes nothing, and nothing on the card moves it: "So sánh" ticks it in place.
 */
export function BidCard({
  application,
  history,
  compared,
  compareFull,
  onCompare,
  onProfile,
  onChoose,
}: {
  application: JobApplication
  history: ReputationHistory | undefined
  compared: boolean
  compareFull: boolean
  onCompare: () => void
  onProfile: () => void
  onChoose: () => void
}) {
  const ids = useId()
  const bidder = application.mentor
  return (
    <li
      aria-labelledby={`${ids}-name`}
      className={cn('flex flex-col gap-4 rounded-lg border bg-card p-4', compared ? 'border-primary' : 'border-border')}
    >
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <span className="flex flex-wrap items-center gap-2">
          <span id={`${ids}-name`} className="text-label font-semibold break-words">
            {bidder.displayName}
          </span>
          {application.invited && (
            <span className="rounded-full bg-muted px-2 py-0.5 text-caption">{t('screen.matching.invitedChip')}</span>
          )}
        </span>
        <span className="text-caption text-muted-foreground">
          {t('screen.matching.list.sent', { date: formatDayMonth(application.submittedAt) })}
        </span>
      </div>
      <section aria-label={t('screen.matching.bid.facts')} className="flex flex-col gap-1.5">
        <p className="text-caption font-semibold text-muted-foreground">{t('screen.matching.bid.facts')}</p>
        <VerifiedFactLines facts={bidder.verified} compact />
      </section>
      <section aria-label={t('screen.matching.bid.posted')} className="flex flex-col gap-1">
        <p className="text-caption font-semibold text-muted-foreground">{t('screen.matching.bid.posted')}</p>
        <p className="text-body-sm">
          <span className="font-semibold">{formatVnd(application.priceVnd)}</span> <TestTag />
          {' · '}
          {t('screen.matching.days', { n: application.timelineDays })}
          {' · '}
          <span className="text-muted-foreground">{experienceText(bidder.yearsOfExperience)}</span>
        </p>
        <p className="line-clamp-3 text-body-sm break-words whitespace-pre-line">{application.approach}</p>
        <p className="text-caption text-muted-foreground">{proofSummary(application)}</p>
      </section>
      <section aria-label={t('screen.matching.bid.history')} className="flex flex-col gap-2">
        <p className="text-caption font-semibold text-muted-foreground">{t('screen.matching.bid.history')}</p>
        <div className="grid gap-2 sm:grid-cols-2">
          <RoleSummaryLine role="mentor" summary={history?.mentor} newcomer={history?.newcomer ?? false} />
          <RoleSummaryLine role="crew" summary={history?.crew} newcomer={false} />
        </div>
      </section>
      <section aria-label={t('screen.matching.bid.metrics')} className="flex flex-col gap-2">
        <p className="text-caption font-semibold text-muted-foreground">{t('screen.matching.bid.metrics')}</p>
        <FitMetricsList metrics={application.fitMetrics} history={history} memberSince={bidder.memberSince} />
      </section>
      <div className="flex flex-wrap items-center gap-3">
        <Button type="button" onClick={onChoose}>
          {t('screen.matching.choose.button')}
        </Button>
        {bidder.githubLogin && (
          <Button type="button" variant="secondary" onClick={onProfile}>
            {t('screen.matching.compare.profile')}
          </Button>
        )}
        <label className={cn('flex items-center gap-2 text-body-sm', compareFull && !compared && 'opacity-60')}>
          <input type="checkbox" checked={compared} disabled={compareFull && !compared} onChange={onCompare} />
          {t('screen.matching.compare.toggle')}
        </label>
      </div>
    </li>
  )
}

/**
 * CUS-12 "Hồ sơ người chào giá" (`?ho-so={bidId}`, UX-DR100): the bid in full with every proof item (a link with N-11's
 * caution line), the bidder's PUB-4 content, and the Fit metrics for this Job; "Chọn" and "So sánh" in its footer.
 * Never contact details.
 */
export function BidderSheet({
  application,
  history,
  compared,
  compareFull,
  onClose,
  onCompare,
  onChoose,
}: {
  application: JobApplication | undefined
  history: ReputationHistory | undefined
  compared: boolean
  compareFull: boolean
  onClose: () => void
  onCompare: () => void
  onChoose: () => void
}) {
  const ids = useId()
  return (
    <Sheet
      open={application !== undefined}
      onClose={onClose}
      title={application?.mentor.displayName ?? t('screen.matching.bid.sheetTitle')}
      description={t('screen.matching.bid.sheetTitle')}
      footer={
        application && (
          <>
            <label className={cn('mr-auto flex items-center gap-2 text-body-sm', compareFull && !compared && 'opacity-60')}>
              <input type="checkbox" checked={compared} disabled={compareFull && !compared} onChange={onCompare} />
              {t('screen.matching.compare.toggle')}
            </label>
            <Button type="button" onClick={onChoose}>
              {t('screen.matching.choose.button')}
            </Button>
          </>
        )
      }
    >
      {application && (
        <>
          <section aria-labelledby={`${ids}-bid`} className="flex flex-col gap-2">
            <h3 id={`${ids}-bid`} className="text-h3">
              {t('screen.matching.bid.sheetBid')}
            </h3>
            <p className="text-body-sm">
              <span className="font-semibold">{formatVnd(application.priceVnd)}</span> <TestTag />
              {' · '}
              {t('screen.matching.days', { n: application.timelineDays })}
            </p>
            <p className="text-caption text-muted-foreground">{t('screen.matching.bid.approach')}</p>
            <p className="text-body-sm break-words whitespace-pre-line">{application.approach}</p>
            <p className="text-caption text-muted-foreground">{t('screen.matching.proof.heading')}</p>
            <ProofList proof={application.proof} />
          </section>
          {application.mentor.githubLogin && (
            <section aria-labelledby={`${ids}-person`} className="flex flex-col gap-2 border-t border-border pt-4">
              <h3 id={`${ids}-person`} className="text-h3">
                {t('screen.matching.bid.sheetPerson')}
              </h3>
              <PersonProfileContent username={application.mentor.githubLogin} />
            </section>
          )}
          <section aria-labelledby={`${ids}-metrics`} className="flex flex-col gap-2 border-t border-border pt-4">
            <h3 id={`${ids}-metrics`} className="text-h3">
              {t('screen.matching.bid.sheetMetrics')}
            </h3>
            <FitMetricsList
              metrics={application.fitMetrics}
              history={history}
              memberSince={application.mentor.memberSince}
            />
          </section>
        </>
      )}
    </Sheet>
  )
}
