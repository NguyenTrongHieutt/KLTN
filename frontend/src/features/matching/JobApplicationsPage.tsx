import { Dialog } from '@base-ui/react/dialog'
import { X } from 'lucide-react'
import { useState } from 'react'
import { Link, Navigate, useNavigate, useParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Sheet } from '@/components/Sheet'
import { Skeleton } from '@/components/Skeleton'
import { useToasts } from '@/components/toasts'
import { TestTag } from '@/components/TestTag'
import { Button } from '@/components/ui/button'
import { useJob, type Job } from '@/features/job/jobApi'
import { JobRoleLine } from '@/features/job/ByJobRole'
import { JobStateBadge } from '@/features/job/JobStateBadge'
import { JobTabs } from '@/features/job/JobTabs'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { MentorProfileContent } from '@/features/team/MentorProfileContent'
import { RatingLine } from '@/features/team/ReputationParts'
import { useMentorReputation, usePublicMentor } from '@/features/team/teamApi'
import { experienceText } from '@/features/team/teamText'
import { t } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatDayMonth } from '@/lib/dates'
import { formatVnd } from '@/lib/money'
import { isNotFound, problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { useJobApplications, useSelectApplication, type JobApplication } from './applicationApi'

/** "So sánh" pins at most this many cards (UX-DR50). */
const MAX_COMPARED = 3

/**
 * CUS-8 "Đơn ứng tuyển" (`/cong-viec/{jobId}/ung-tuyen`, FR-13, FR-15, UX-DR50, UX-DR66): the open
 * Applications to the Customer's Job with each Mentor's Rating summary and Portfolio count, the profile
 * in a Sheet, "So sánh" for up to three side by side, and "Chọn", which applies J3 at the version the
 * screen loaded. No Student appears here. A Job past Open has no applications tab.
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
        {job.data && <ApplicationList job={job.data} />}
      </RegionState>
    </div>
  )
}

function ApplicationList({ job }: { job: Job }) {
  const applications = useJobApplications(job.id)
  const items = applications.data ?? []
  const [compared, setCompared] = useState<string[]>([])
  const [profile, setProfile] = useState<string>()
  const [choosing, setChoosing] = useState<JobApplication>()
  const [stale, setStale] = useState(false)
  const toggle = (id: string) =>
    setCompared((current) =>
      current.includes(id) ? current.filter((other) => other !== id) : current.length < MAX_COMPARED ? [...current, id] : current,
    )
  const pinned = items.filter((item) => compared.includes(item.id))
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
            {pinned.length > 0 && (
              <section aria-labelledby="compare-heading" className="flex flex-col gap-3">
                <h2 id="compare-heading" className="text-h2">
                  {t('screen.matching.compare.heading', { n: pinned.length })}
                </h2>
                <ul className="grid gap-3 lg:grid-cols-3">
                  {pinned.map((application) => (
                    <ApplicationCard
                      key={application.id}
                      application={application}
                      compared
                      onCompare={() => toggle(application.id)}
                      onProfile={setProfile}
                      onChoose={() => setChoosing(application)}
                    />
                  ))}
                </ul>
                <p className="sticky bottom-0 z-10 rounded-md border border-border bg-card px-4 py-2 text-label shadow-sm lg:hidden">
                  {t('screen.matching.compare.summary', { n: pinned.length })}
                </p>
              </section>
            )}
            <section aria-labelledby="all-applications" className="flex flex-col gap-3">
              <h2 id="all-applications" className="text-h2">
                {t('screen.matching.compare.all', { n: items.length })}
              </h2>
              <ul className="flex flex-col gap-3">
                {items
                  .filter((item) => !compared.includes(item.id))
                  .map((application) => (
                    <ApplicationCard
                      key={application.id}
                      application={application}
                      compared={false}
                      compareFull={compared.length >= MAX_COMPARED}
                      onCompare={() => toggle(application.id)}
                      onProfile={setProfile}
                      onChoose={() => setChoosing(application)}
                    />
                  ))}
              </ul>
            </section>
          </>
        )}
      </RegionState>
      <div role="note" className="rounded-md border-l-4 border-primary bg-primary-soft p-4 text-body-sm">
        {t('screen.matching.compare.note')}
      </div>
      <ProfileSheet username={profile} onClose={() => setProfile(undefined)} />
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

function ApplicationCard({
  application,
  compared,
  compareFull = false,
  onCompare,
  onProfile,
  onChoose,
}: {
  application: JobApplication
  compared: boolean
  compareFull?: boolean
  onCompare: () => void
  onProfile: (username: string) => void
  onChoose: () => void
}) {
  const mentor = application.mentor
  const reputation = useMentorReputation(mentor.githubLogin ?? undefined)
  return (
    <li className={cn('flex flex-col gap-2 rounded-lg border bg-card p-4', compared ? 'border-primary' : 'border-border')}>
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <span className="flex flex-wrap items-center gap-2">
          <span className="text-label font-semibold break-words">{mentor.displayName}</span>
          {application.invited && (
            <span className="rounded-full bg-muted px-2 py-0.5 text-caption">{t('screen.matching.invitedChip')}</span>
          )}
        </span>
        <span className="text-caption text-muted-foreground">
          {t('screen.matching.list.sent', { date: formatDayMonth(application.submittedAt) })}
        </span>
      </div>
      <p className="text-caption text-muted-foreground">
        {t('screen.matching.list.mentor', { experience: experienceText(mentor.yearsOfExperience), n: mentor.teamSize })}
        {mentor.githubLogin && (
          <>
            {' · '}
            <button
              type="button"
              onClick={() => onProfile(mentor.githubLogin ?? '')}
              className="text-primary underline-offset-4 hover:underline"
            >
              {t('screen.matching.compare.profile')}
            </button>
          </>
        )}
      </p>
      <p className="text-body-sm">
        <span className="font-semibold">{formatVnd(application.priceVnd)}</span>{' '}
        <TestTag />
        {' · '}
        {t('screen.matching.days', { n: application.timelineDays })}
      </p>
      <p className="line-clamp-3 text-body-sm break-words whitespace-pre-line">{application.approach}</p>
      {reputation.data && (
        <div className="flex flex-wrap items-center gap-2">
          <RatingLine
            average={reputation.data.average}
            count={reputation.data.count}
            from="customers"
            newcomer={reputation.data.newcomer}
          />
          <span className="text-caption text-muted-foreground">
            {t('screen.matching.compare.portfolio', { n: reputation.data.portfolio.length })}
          </span>
        </div>
      )}
      <div className="flex flex-wrap items-center gap-3">
        <Button type="button" onClick={onChoose}>
          {t('screen.matching.choose.button')}
        </Button>
        <label className={cn('flex items-center gap-2 text-body-sm', compareFull && !compared && 'opacity-60')}>
          <input type="checkbox" checked={compared} disabled={compareFull && !compared} onChange={onCompare} />
          {t('screen.matching.compare.toggle')}
        </label>
      </div>
    </li>
  )
}

/** PUB-5's content in a Sheet, so the Customer keeps their place (UX-DR50). */
function ProfileSheet({ username, onClose }: { username: string | undefined; onClose: () => void }) {
  const mentor = usePublicMentor(username)
  return (
    <Sheet open={username !== undefined} onClose={onClose} title={mentor.data?.displayName ?? t('screen.matching.compare.profile')}>
      <RegionState query={mentor} skeleton={<Skeleton className="h-64 w-full" />}>
        {mentor.data && <MentorProfileContent mentor={mentor.data} visitor={false} />}
      </RegionState>
    </Sheet>
  )
}

/** "Chọn {mentor}?" lists what will happen before J3 (UX-DR41). */
function ChooseDialog({
  job,
  application,
  others,
  onClose,
  onStale,
}: {
  job: Job
  application: JobApplication
  others: number
  onClose: () => void
  onStale: () => void
}) {
  const select = useSelectApplication(job.id)
  const toasts = useToasts()
  const navigate = useNavigate()
  const mentor = application.mentor.displayName
  const confirm = () =>
    select.mutate(
      { applicationId: application.id, version: job.version },
      {
        onSuccess: () => {
          toasts.success(t('screen.matching.choose.done', { mentor }))
          void navigate(`/cong-viec/${job.id}`)
        },
        onError: (error) => {
          if (error instanceof ApiError && error.problem.code === 'STALE') {
            onStale()
            return
          }
          onClose()
          toasts.error(problemMessage(error, { module: 'job', action: t('screen.matching.choose.action') }).text)
        },
      },
    )
  return (
    <Dialog.Root open onOpenChange={(open) => !open && onClose()}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
        <Dialog.Popup className="fixed top-24 left-1/2 z-50 flex w-[min(560px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
          <div className="flex items-start justify-between gap-3">
            <Dialog.Title className="text-h2 break-words">{t('screen.matching.choose.title', { mentor })}</Dialog.Title>
            <Dialog.Close aria-label={t('screen.product.finding.close')} className="rounded-md p-1 text-muted-foreground hover:bg-muted">
              <X aria-hidden="true" className="size-5" />
            </Dialog.Close>
          </div>
          <div className="flex flex-col gap-1.5">
            <p className="text-label font-semibold">{t('screen.matching.choose.what')}</p>
            <ul className="flex list-disc flex-col gap-1 pl-5 text-body-sm">
              <li>{t('screen.matching.choose.nda', { mentor })}</li>
              {others > 0 && <li>{t('screen.matching.choose.hold', { n: others })}</li>}
              <li>{t('screen.matching.choose.closed')}</li>
              <li>{t('screen.matching.choose.money')}</li>
            </ul>
          </div>
          <div className="flex flex-col gap-1 rounded-md bg-muted p-3 text-body-sm">
            <p>
              {formatVnd(application.priceVnd)}{' '}
              <TestTag />
            </p>
            <p>{t('screen.matching.days', { n: application.timelineDays })}</p>
          </div>
          <div className="flex flex-wrap justify-between gap-3">
            <Dialog.Close render={<Button variant="secondary" />}>{t('screen.matching.choose.back')}</Dialog.Close>
            <Button type="button" onClick={confirm} disabled={select.isPending}>
              {t('screen.matching.choose.confirm', { mentor })}
            </Button>
          </div>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}

/** The Stale dialog (UX-DR26): the decision was not sent; reload and decide again, never re-send. */
function StaleDialog({ open, onClose, jobId }: { open: boolean; onClose: () => void; jobId: string }) {
  const job = useJob(jobId)
  const applications = useJobApplications(jobId)
  const reload = () => {
    void job.refetch()
    void applications.refetch()
    onClose()
  }
  return (
    <Dialog.Root open={open} onOpenChange={(next) => !next && onClose()}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
        <Dialog.Popup className="fixed top-24 left-1/2 z-50 flex w-[min(480px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
          <Dialog.Title className="text-h2">{t('error.STALE')}</Dialog.Title>
          <Dialog.Description className="text-body-sm">{t('screen.matching.stale.body')}</Dialog.Description>
          <div className="flex justify-end">
            <Button type="button" onClick={reload}>
              {t('screen.matching.stale.reload')}
            </Button>
          </div>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
