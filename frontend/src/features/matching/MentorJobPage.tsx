import { Dialog } from '@base-ui/react/dialog'
import { jobAside, jobHeaderCard } from '@/features/job/jobFrame'
import { X } from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { buttonVariants } from '@/components/ui/button-variants'
import { NoCrewBanner } from '@/features/crew/NoCrew'
import { JobPhaseBadge, JobRoleLine } from '@/features/job/ByJobRole'
import { JobStateBadge } from '@/features/job/JobStateBadge'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t, type MessageKey } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatDayMonth } from '@/lib/dates'
import { isNotFound, problemMessage } from '@/lib/problemMessage'
import { MentorJobTabs } from './MentorJobTabs'
import { StageStrip } from '@/features/job/StageStrip'
import { useDeclineSelection, useMentorJob, useSignNda, type MentorJob } from './mentorJobApi'
import { NDA_VERSION } from './nda'
import { CommitEmailNotice, NdaText } from './NdaText'
import { ProgressOverview } from '@/features/job/progress/ProgressOverview'
import { DisputeSection } from '@/features/dispute/DisputeSection'
import { CancellationSection } from '@/features/job/cancellation/CancellationSection'
import { SetupTracker } from '@/features/job/SetupTracker'
import { hasStarted } from '@/features/job/jobState'
import { Page } from '@/components/ui/page'
import { DialogBackdrop, DialogPopup } from '@/components/ui/dialog'
import { Card } from '@/components/ui/card'

/**
 * JOB-1 for the Job's Mentor (FR-15, UX-DR52): while Selecting, the NDA panel with N-14, the NDA text
 * and its version, and for a Code Job N-23 with "Tôi đã hiểu"; "Ký cam kết" applies J4 and "Từ chối"
 * J5 after a confirmation. From Agreement on, what comes next.
 */
export function MentorJobPage({ jobId }: { jobId: string }) {
  const job = useMentorJob(jobId)
  usePageTitle(job.data?.title ?? t('screen.job.title'))
  if (job.isError && isNotFound(job.error)) {
    return <NotFoundScreen />
  }
  return (
    <Page>
      <RegionState query={job} skeleton={<Skeleton className="h-96 w-full" />}>
        {job.data && <Overview job={job.data} />}
      </RegionState>
    </Page>
  )
}

function Overview({ job }: { job: MentorJob }) {
  const types = job.jobTypes.map((type) => t(`term.jobType${type}.label` as MessageKey)).join(', ')
  return (
    <>
      <div className={jobHeaderCard}>
        <nav aria-label={t('screen.job.detail.breadcrumb')} className="text-caption text-muted-foreground">
          <Link to="/cong-viec" className="text-primary underline-offset-4 hover:underline">
            {t('screen.job.detail.breadcrumb')}
          </Link>{' '}
          / <span className="break-words">{job.title}</span>
        </nav>
        <div className="flex flex-wrap items-center gap-2">
          <PageHeading>{job.title}</PageHeading>
          <JobStateBadge state={job.state} audience="mentor" />
          <JobPhaseBadge jobId={job.id} />
        </div>
        <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
          <p className="text-caption text-muted-foreground break-words">
            {t('screen.nda.meta', { product: job.productName, types, date: formatDayMonth(job.selectedAt) })}
          </p>
          <JobRoleLine jobId={job.id} />
        </div>
        <StageStrip state={job.state} />
        <NoCrewBanner jobId={job.id} />
        <MentorJobTabs jobId={job.id} state={job.state} current="overview" payment={job.agreementConfirmed} />
      </div>
      {job.state === 'SELECTING' ? (
        <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_300px]">
          <NdaPanel job={job} />
          <aside className={jobAside}>
            <Card as="section" aria-labelledby="after-signing" className="flex flex-col gap-2">
              <h3 id="after-signing" className="text-h3">
                {t('screen.nda.afterTitle')}
              </h3>
              <ol className="flex list-decimal flex-col gap-1.5 pl-5 text-body-sm">
                <li>{t('screen.nda.afterChat')}</li>
                <li>{t('screen.nda.afterAgreement')}</li>
                <li>{t('screen.nda.afterCrew')}</li>
              </ol>
            </Card>
            <Card as="section" aria-labelledby="customer-sees" className="flex flex-col gap-1.5 text-body-sm">
              <h3 id="customer-sees" className="text-h3">
                {t('screen.nda.customerSeesTitle')}
              </h3>
              <p className="text-muted-foreground">{t('screen.nda.customerSees')}</p>
            </Card>
          </aside>
        </div>
      ) : hasStarted(job.state) || job.state === 'CANCELLED' ? (
        <>
          {/* R6: an Assessment Job's read invitations are still out after J7, so the tracker stays (Story 23.11b). */}
          <SetupTracker jobId={job.id} />
          <ProgressOverview jobId={job.id} audience="mentor" optional={job.state === 'CANCELLED'} />
        </>
      ) : (
        <>
          {job.state === 'AGREEMENT' && <AgreementStep job={job} />}
          {/* The Mentor reads the same step tracker the Customer and the Crew do, and with it why a start is blocked. */}
          <SetupTracker jobId={job.id} />
          {(job.state === 'AGREEMENT' || job.state === 'STARTING') && (
            <>
              <DisputeSection jobId={job.id} />
              <CancellationSection jobId={job.id} />
            </>
          )}
        </>
      )}
    </>
  )
}

/**
 * While the Agreement is open: what the Mentor does next, and the tabs it happens in. Until the Agreement is confirmed,
 * drafting it and recruiting the Crew; after that, only the Crew is missing (R7).
 */
function AgreementStep({ job }: { job: MentorJob }) {
  const confirmed = job.agreementConfirmed
  return (
    <Card as="section" className="flex flex-col gap-2">
      <h2 className="text-h3">{t(confirmed ? 'screen.nda.confirmedTitle' : 'screen.nda.signedTitle')}</h2>
      <p className="text-body-sm text-muted-foreground">{t(confirmed ? 'screen.nda.confirmedBody' : 'screen.nda.signedBody')}</p>
      <div className="flex flex-wrap gap-2 pt-1">
        {!confirmed && (
          <Link to={`/cong-viec/${job.id}/thoa-thuan`} className={buttonVariants({ size: 'compact' })}>
            {t('screen.nda.openDraft')}
          </Link>
        )}
        <Link
          to={`/cong-viec/${job.id}/tuyen-thanh-vien`}
          className={buttonVariants({ variant: confirmed ? 'primary' : 'secondary', size: 'compact' })}
        >
          {t('screen.recruitment.tab')}
        </Link>
      </div>
    </Card>
  )
}

function NdaPanel({ job }: { job: MentorJob }) {
  const sign = useSignNda(job.id)
  const toasts = useToasts()
  const [understood, setUnderstood] = useState(false)
  const [declining, setDeclining] = useState(false)
  const needsNotice = job.codeJob && !job.commitEmailNoticeAcknowledged
  const blocked = needsNotice && !understood
  const submit = () =>
    sign.mutate(
      { ndaVersion: NDA_VERSION, commitEmailNoticeAcknowledged: understood || job.commitEmailNoticeAcknowledged },
      {
        onSuccess: () => toasts.success(t('screen.nda.signed')),
        onError: (error) => {
          const message = problemMessage(error, { module: 'matching' })
          toasts.error(message.text, message.link)
        },
      },
    )
  return (
    <section aria-labelledby="nda-heading" className="flex flex-col gap-4 rounded-lg border-2 border-primary bg-card p-5">
      <h2 id="nda-heading" className="text-h2">
        {t('screen.nda.title', { version: NDA_VERSION })}
      </h2>
      <NdaText />
      {needsNotice && <CommitEmailNotice understood={understood} onChange={setUnderstood} />}
      <div className="flex flex-wrap justify-between gap-3">
        <Button type="button" variant="secondary" onClick={() => setDeclining(true)}>
          {t('screen.nda.decline')}
        </Button>
        <Button type="button" onClick={submit} disabled={blocked || sign.isPending} aria-describedby="nda-why">
          {t('screen.nda.sign')}
        </Button>
      </div>
      <p id="nda-why" className="text-caption text-muted-foreground">
        {t(needsNotice ? 'screen.nda.whyNotice' : 'screen.nda.why', {
          date: job.ndaDueAt ? formatDayMonth(job.ndaDueAt) : '',
        })}
      </p>
      {declining && <DeclineDialog job={job} onClose={() => setDeclining(false)} />}
    </section>
  )
}

function DeclineDialog({ job, onClose }: { job: MentorJob; onClose: () => void }) {
  const decline = useDeclineSelection(job.id)
  const toasts = useToasts()
  const navigate = useNavigate()
  const confirm = () =>
    decline.mutate(job.version, {
      onSuccess: () => {
        toasts.success(t('screen.nda.declined'))
        void navigate('/cong-viec')
      },
      onError: (error) => {
        onClose()
        toasts.error(
          error instanceof ApiError && error.problem.code === 'STALE'
            ? t('error.STALE')
            : problemMessage(error, { module: 'job' }).text,
        )
      },
    })
  return (
    <Dialog.Root open onOpenChange={(open) => !open && onClose()}>
      <Dialog.Portal>
        <DialogBackdrop />
        <DialogPopup>
          <div className="flex items-start justify-between gap-3">
            <Dialog.Title className="text-h2">{t('screen.nda.declineTitle')}</Dialog.Title>
            <Dialog.Close aria-label={t('screen.shared.close')} className="rounded-md p-1 text-muted-foreground hover:bg-muted">
              <X aria-hidden="true" className="size-5" />
            </Dialog.Close>
          </div>
          <Dialog.Description className="text-body-sm">{t('screen.nda.declineBody')}</Dialog.Description>
          <div className="flex flex-wrap justify-between gap-3">
            <Dialog.Close render={<Button variant="secondary" />}>{t('screen.nda.declineBack')}</Dialog.Close>
            <Button type="button" variant="destructive" onClick={confirm} disabled={decline.isPending}>
              {t('screen.nda.decline')}
            </Button>
          </div>
        </DialogPopup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
