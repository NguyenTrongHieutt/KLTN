import { useState } from 'react'
import { Link, Navigate, useParams, useSearchParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { buttonVariants } from '@/components/ui/button-variants'
import { Button } from '@/components/ui/button'
import { useJob, type Job } from '@/features/job/jobApi'
import { JobRoleLine } from '@/features/job/ByJobRole'
import { JobStateBadge } from '@/features/job/JobStateBadge'
import { JobTabs } from '@/features/job/JobTabs'
import { useReputationHistories } from '@/features/reputation/historyApi'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t } from '@/i18n/t'
import { formatDayMonth } from '@/lib/dates'
import { isNotFound } from '@/lib/problemMessage'
import { useJobApplications, type JobApplication } from './applicationApi'
import { BidCard, BidderSheet } from './BidCard'
import { ChooseDialog, StaleDialog } from './ChooseBid'
import { comparePath, MAX_COMPARED } from './compare'

/** CUS-12's place in the URL (UX-DR100): `?ho-so={bidId}`. */
const PROFILE = 'ho-so'

/**
 * CUS-8 "Chào giá" (`/cong-viec/{jobId}/ung-tuyen`, FR-15, UX-DR100, Story 21.3): every bid on the Customer's Job as a
 * Bid card, oldest first and never reordered, not by a metric, a Rating, or an invitation. "Xem hồ sơ" opens CUS-12
 * (`?ho-so={bidId}`); "So sánh" ticks up to three cards in place, and the bar opens CUS-13 with them; "Chọn" applies J3
 * at the version the screen loaded. No Team size and no score anywhere. A Job past Open has no bids tab.
 */
export function JobApplicationsPage() {
  const { jobId } = useParams()
  const job = useJob(jobId)
  usePageTitle(job.data ? t('screen.matching.list.title', { job: job.data.title }) : t('screen.job.title'))
  if (job.isError && isNotFound(job.error)) {
    return <NotFoundScreen />
  }
  if (job.data?.state === 'DRAFT') {
    return <Navigate to={`/cong-viec/${job.data.id}/sua`} replace />
  }
  if (job.data && job.data.state !== 'OPEN') {
    return <Navigate to={`/cong-viec/${job.data.id}`} replace />
  }
  return (
    <div className="mx-auto flex max-w-[1120px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <RegionState query={job} skeleton={<Skeleton className="h-96 w-full" />}>
        {job.data && <BidList job={job.data} />}
      </RegionState>
    </div>
  )
}

function BidList({ job }: { job: Job }) {
  const applications = useJobApplications(job.id)
  const items = applications.data ?? []
  const histories = useReputationHistories(
    items.map((item) => item.mentor.githubLogin).filter((login): login is string => !!login),
  )
  const [params, setParams] = useSearchParams()
  const [ticked, setTicked] = useState<string[]>([])
  const [choosing, setChoosing] = useState<JobApplication>()
  const [stale, setStale] = useState(false)
  const profile = items.find((item) => item.id === params.get(PROFILE))
  // A ticked bid that left the list (withdrawn, say) counts no more and blocks no other tick.
  const compared = ticked.filter((id) => items.some((item) => item.id === id))
  const toggle = (id: string) =>
    setTicked((current) => {
      const live = current.filter((other) => items.some((item) => item.id === other))
      return live.includes(id) ? live.filter((other) => other !== id) : live.length < MAX_COMPARED ? [...live, id] : live
    })
  const openProfile = (id: string | undefined) => {
    const next = new URLSearchParams(params)
    if (id) {
      next.set(PROFILE, id)
    } else {
      next.delete(PROFILE)
    }
    setParams(next, { replace: id === undefined })
  }
  const full = compared.length >= MAX_COMPARED
  return (
    <>
      <div className="flex flex-col gap-3">
        <nav aria-label={t('screen.job.detail.breadcrumb')} className="text-caption text-muted-foreground">
          <Link to="/cong-viec" className="text-primary underline-offset-4 hover:underline">
            {t('screen.job.detail.breadcrumb')}
          </Link>{' '}
          / <span className="break-words">{job.title}</span>
        </nav>
        <div className="flex flex-wrap items-center gap-2">
          <PageHeading>{job.title}</PageHeading>
          <JobStateBadge state={job.state} />
        </div>
        <JobRoleLine jobId={job.id} state={job.state} />
        <JobTabs jobId={job.id} current="applications" applications={items.length} />
      </div>
      <RegionState query={applications} skeleton={<Skeleton className="h-48 w-full" />}>
        {items.length === 0 ? (
          <div className="flex flex-col gap-1 rounded-lg border border-border bg-card p-8 text-center">
            <p className="text-label font-semibold">{t('screen.job.detail.applicationsTitle')}</p>
            {job.lapsesAt && (
              <p className="text-body-sm text-muted-foreground">
                {t('screen.job.detail.lapseNote', { date: formatDayMonth(job.lapsesAt) })}
              </p>
            )}
          </div>
        ) : (
          <>
            <p className="text-body-sm text-muted-foreground">{t('screen.matching.compare.lead')}</p>
            <ul className="flex flex-col gap-3">
              {items.map((application) => (
                <BidCard
                  key={application.id}
                  application={application}
                  histories={histories}
                  compared={compared.includes(application.id)}
                  compareFull={full}
                  onCompare={() => toggle(application.id)}
                  onProfile={() => openProfile(application.id)}
                  onChoose={() => setChoosing(application)}
                />
              ))}
            </ul>
          </>
        )}
      </RegionState>
      {compared.length > 0 && <CompareBar job={job} ids={compared} onClear={() => setTicked([])} />}
      <div role="note" className="rounded-md border-l-4 border-primary bg-primary-soft p-4 text-body-sm">
        {t('screen.matching.compare.note')}
      </div>
      <BidderSheet
        application={profile}
        histories={histories}
        compared={profile ? compared.includes(profile.id) : false}
        compareFull={full}
        onClose={() => openProfile(undefined)}
        onCompare={() => profile && toggle(profile.id)}
        onChoose={() => {
          if (profile) {
            openProfile(undefined)
            setChoosing(profile)
          }
        }}
      />
      {choosing && (
        <ChooseDialog
          job={job}
          application={choosing}
          others={items.length - 1}
          onClose={() => setChoosing(undefined)}
          onStale={() => {
            setChoosing(undefined)
            setStale(true)
          }}
        />
      )}
      <StaleDialog open={stale} onClose={() => setStale(false)} jobId={job.id} />
    </>
  )
}

/** The bar that opens CUS-13 with the ticked bids (UX-DR101): it waits for two of them. */
function CompareBar({ job, ids, onClear }: { job: Job; ids: string[]; onClear: () => void }) {
  const ready = ids.length >= 2
  return (
    <div
      role="region"
      aria-label={t('screen.matching.compare.toggle')}
      className="sticky bottom-0 z-10 flex flex-wrap items-center justify-between gap-3 rounded-md border border-border bg-card px-4 py-3 shadow-sm"
    >
      <span className="text-body-sm text-muted-foreground">
        {ids.length >= MAX_COMPARED ? t('screen.matching.compare.full') : t('screen.matching.compare.barHint')}
      </span>
      <span className="flex flex-wrap items-center gap-2">
        <Button type="button" variant="ghost" size="compact" onClick={onClear}>
          {t('screen.matching.compare.clear')}
        </Button>
        {ready ? (
          <Link to={comparePath(job.id, ids)} className={buttonVariants()}>
            {t('screen.matching.compare.bar', { n: ids.length })}
          </Link>
        ) : (
          <Button type="button" disabled>
            {t('screen.matching.compare.bar', { n: ids.length })}
          </Button>
        )}
      </span>
    </div>
  )
}
