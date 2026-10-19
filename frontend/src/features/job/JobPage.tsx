import { Dialog } from '@base-ui/react/dialog'
import { Clock, TriangleAlert, X } from 'lucide-react'
import { useState } from 'react'
import { Link, Navigate, useNavigate, useParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { useSession } from '@/features/identity/useSession'
import { useProduct } from '@/features/product/productApi'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t, type MessageKey } from '@/i18n/t'
import { formatCalendarDate, formatDayMonth } from '@/lib/dates'
import { isNotFound, problemMessage } from '@/lib/problemMessage'
import { useJobApplications } from '@/features/matching/applicationApi'
import { useJob, useWithdrawJob, type Job } from './jobApi'
import { JobTabs } from './JobTabs'
import { JobStateBadge } from './JobStateBadge'

/**
 * JOB-1 Tổng quan (`/cong-viec/{jobId}`, UX-DR17, UX-DR19, UX-DR68) for the Customer. While the Job is
 * Open: the Job header, what happens next, the Job Post as Mentors read it, the reminder that the code
 * is public, the lapse date, and "Rút tin" (J2). A Draft opens CUS-6; the later states' overview
 * comes with their stories.
 */
export function JobPage() {
  const { jobId } = useParams()
  const job = useJob(jobId)
  const role = useSession().data?.account?.role
  usePageTitle(job.data?.title ?? t('screen.job.title'))
  if ((role !== undefined && role !== 'CUSTOMER') || (job.isError && isNotFound(job.error))) {
    return <NotFoundScreen />
  }
  if (job.data?.state === 'DRAFT') {
    return <Navigate to={`/cong-viec/${job.data.id}/sua`} replace />
  }
  return (
    <div className="mx-auto flex max-w-[1120px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <RegionState query={job} skeleton={<Skeleton className="h-96 w-full" />}>
        {job.data && <Overview job={job.data} />}
      </RegionState>
    </div>
  )
}

function Overview({ job }: { job: Job }) {
  const applications = useJobApplications(job.state === 'OPEN' ? job.id : undefined).data ?? []
  const types = job.jobTypes.map((type) => t(`term.jobType${type}.label` as MessageKey)).join(', ')
  const open = job.state === 'OPEN' && job.lapsesAt != null
  return (
    <>
      <div className="flex flex-col gap-3">
        <nav aria-label={t('screen.job.detail.breadcrumb')} className="text-caption text-muted-foreground">
          <Link to="/cong-viec" className="text-primary underline-offset-4 hover:underline">
            {t('screen.job.detail.breadcrumb')}
          </Link>{' '}
          / <span className="break-words">{job.title}</span>
        </nav>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="flex min-w-0 flex-col gap-1">
            <div className="flex flex-wrap items-center gap-2">
              <PageHeading>{job.title}</PageHeading>
              <JobStateBadge state={job.state} />
            </div>
            <p className="text-caption text-muted-foreground break-words">
              {job.productName} · {types}
            </p>
          </div>
          {job.state === 'OPEN' && <Withdraw job={job} />}
        </div>
        <JobTabs jobId={job.id} current="overview" applications={job.state === 'OPEN' ? applications.length : undefined} />
      </div>
      {job.state === 'SELECTING' && job.mentor && job.ndaDueAt ? (
        <section aria-labelledby="job-selecting" className="flex flex-col gap-2 rounded-lg border border-border bg-card p-5">
          <h2 id="job-selecting" className="text-h2">
            {t('screen.matching.selecting.title', { mentor: job.mentor.displayName })}
          </h2>
          <p className="text-body-sm">
            {t('screen.matching.selecting.body', { mentor: job.mentor.displayName, date: formatDayMonth(job.ndaDueAt) })}
          </p>
          {job.mentor.githubLogin && (
            <Link to={`/mentor/${job.mentor.githubLogin}`} className="w-fit text-body-sm text-primary underline-offset-4 hover:underline">
              {t('screen.matching.selecting.profile')}
            </Link>
          )}
        </section>
      ) : open && job.lapsesAt ? (
        <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_280px]">
          <div className="flex min-w-0 flex-col gap-6">
            <NextSteps lapsesAt={job.lapsesAt} jobId={job.id} applications={applications.length} />
            <JobPost job={job} />
            <div role="note" className="flex gap-3 rounded-md border-l-4 border-warning bg-warning-soft p-4">
              <TriangleAlert aria-hidden="true" className="size-5 shrink-0 text-warning" />
              <p className="text-body-sm">{t('screen.job.detail.publicCode')}</p>
            </div>
          </div>
          <Aside job={job} lapsesAt={job.lapsesAt} />
        </div>
      ) : (
        <p className="text-body-sm text-muted-foreground">{t('screen.job.detail.notYet')}</p>
      )}
    </>
  )
}

function LapseChip({ lapsesAt }: { lapsesAt: string }) {
  return (
    <span className="inline-flex w-fit items-center gap-1 rounded-full bg-warning-soft px-2 py-0.5 text-caption font-semibold text-warning">
      <Clock aria-hidden="true" className="size-4" />
      {t('screen.job.detail.lapsesChip', { date: formatDayMonth(lapsesAt) })}
    </span>
  )
}

/** "Chưa có đơn ứng tuyển" and the three steps of "Điều gì xảy ra tiếp theo" (UX-DR68). */
function NextSteps({ lapsesAt, jobId, applications }: { lapsesAt: string; jobId: string; applications: number }) {
  const steps = [
    ['nextApply', 'nextApplyBody'],
    ['nextChoose', 'nextChooseBody'],
    ['nextSign', 'nextSignBody'],
  ] as const
  return (
    <section aria-labelledby="job-applications" className="flex flex-col gap-3 rounded-lg border border-border bg-card p-5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id="job-applications" className="text-h2">
          {applications > 0
            ? t('screen.matching.overviewCount', { n: applications })
            : t('screen.job.detail.applicationsTitle')}
        </h2>
        <LapseChip lapsesAt={lapsesAt} />
      </div>
      {applications > 0 ? (
        <Link to={`/cong-viec/${jobId}/ung-tuyen`} className="w-fit text-body-sm text-primary underline-offset-4 hover:underline">
          {t('screen.matching.overviewOpen')}
        </Link>
      ) : (
        <p className="text-body-sm text-muted-foreground">{t('screen.job.detail.applicationsBody')}</p>
      )}
      <h3 className="text-h3">{t('screen.job.detail.nextTitle')}</h3>
      <ol className="flex flex-col gap-3">
        {steps.map(([title, body], index) => (
          <li key={title} className="flex gap-3">
            <span
              aria-hidden="true"
              className="flex size-7 shrink-0 items-center justify-center rounded-full bg-primary-soft text-label font-semibold text-primary"
            >
              {index + 1}
            </span>
            <span className="flex flex-col gap-0.5">
              <span className="text-label font-semibold">{t(`screen.job.detail.${title}`)}</span>
              <span className="text-body-sm text-muted-foreground">{t(`screen.job.detail.${body}`)}</span>
            </span>
          </li>
        ))}
      </ol>
      <p className="text-caption text-muted-foreground">
        {t('screen.job.detail.lapseNote', { date: formatDayMonth(lapsesAt) })}
      </p>
    </section>
  )
}

/** The Job Post as approved Mentors read it (FR-9, FR-12). */
function JobPost({ job }: { job: Job }) {
  const answers = [
    ['problem', job.problem],
    ['expected', job.expected],
    ['appUsers', job.appUsers],
    ['doneCheck', job.doneCheck],
  ] as const
  return (
    <section aria-labelledby="job-post" className="flex flex-col gap-3 rounded-lg border border-border bg-card p-5">
      <h2 id="job-post" className="text-h2">
        {t('screen.job.detail.postTitle')}
      </h2>
      {answers.map(
        ([field, answer]) =>
          answer && (
            <div key={field} className="flex flex-col gap-1 text-body-sm">
              <p className="text-label font-semibold">{t(`screen.job.detail.${field}`)}</p>
              <p className="break-words whitespace-pre-line">{answer}</p>
            </div>
          ),
      )}
      {job.skills.length > 0 && (
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="text-label font-semibold text-body-sm">{t('screen.job.detail.skills')}</span>
          {job.skills.map((skill) => (
            <span key={skill.id} className="rounded-full bg-muted px-2 py-0.5 text-caption">
              {skill.name}
            </span>
          ))}
        </div>
      )}
      <p className="text-body-sm">
        {job.budget && (
          <>
            {t('screen.job.detail.budget', { budget: t(`screen.job.form.budget${job.budget}` as MessageKey) })}{' '}
            <span className="rounded bg-warning-soft px-1 text-caption font-semibold text-warning">
              {t('screen.job.detail.test')}
            </span>
          </>
        )}
        {job.openedAt && <> · {t('screen.job.detail.openedOn', { date: formatDayMonth(job.openedAt) })}</>}
      </p>
      {job.deadline && (
        <p className="text-body-sm">{t('screen.job.detail.deadline', { date: formatCalendarDate(job.deadline) })}</p>
      )}
    </section>
  )
}

function Aside({ job, lapsesAt }: { job: Job; lapsesAt: string }) {
  const product = useProduct(job.productId)
  const findings = product.data?.openFindings ?? 0
  const assessment = job.jobTypes.includes('ASSESSMENT')
  return (
    <aside className="flex flex-col gap-4">
      <section aria-labelledby="job-upcoming" className="flex flex-col gap-2 rounded-lg border border-border bg-card p-4">
        <h3 id="job-upcoming" className="text-h3">
          {t('screen.job.detail.upcoming')}
        </h3>
        <div className="flex items-center justify-between gap-2 text-body-sm">
          <span>{t('screen.job.detail.lapses')}</span>
          <span className="rounded-full bg-warning-soft px-2 py-0.5 text-caption font-semibold text-warning">
            {formatDayMonth(lapsesAt)}
          </span>
        </div>
      </section>
      <section aria-labelledby="job-product" className="flex flex-col gap-1.5 rounded-lg border border-border bg-card p-4 text-body-sm">
        <h3 id="job-product" className="text-h3">
          {t('screen.job.detail.product')}
        </h3>
        <Link to={`/san-pham/${job.productId}`} className="break-words text-primary underline-offset-4 hover:underline">
          {job.productName}
        </Link>
        {findings > 0 && (
          <p className="text-muted-foreground">
            {t(assessment ? 'screen.job.detail.productFindingsAssessment' : 'screen.job.detail.productFindings', {
              n: findings,
            })}
          </p>
        )}
      </section>
    </aside>
  )
}

/** "Rút tin" (J2): the Job Post returns to Draft, after a confirmation. */
function Withdraw({ job }: { job: Job }) {
  const [open, setOpen] = useState(false)
  const withdraw = useWithdrawJob()
  const toasts = useToasts()
  const navigate = useNavigate()
  const confirm = () =>
    withdraw.mutate(job.id, {
      onSuccess: () => {
        setOpen(false)
        toasts.success(t('screen.job.detail.withdrawn'))
        void navigate(`/cong-viec/${job.id}/sua`)
      },
      onError: (error) => {
        setOpen(false)
        toasts.error(problemMessage(error, { module: 'job' }).text)
      },
    })
  return (
    <>
      <Button type="button" variant="secondary" onClick={() => setOpen(true)}>
        {t('screen.job.detail.withdraw')}
      </Button>
      <Dialog.Root open={open} onOpenChange={setOpen}>
        <Dialog.Portal>
          <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
          <Dialog.Popup className="fixed top-24 left-1/2 z-50 flex w-[min(520px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
            <div className="flex items-start justify-between gap-3">
              <Dialog.Title className="text-h2">{t('screen.job.detail.withdrawTitle')}</Dialog.Title>
              <Dialog.Close
                aria-label={t('screen.product.finding.close')}
                className="rounded-md p-1 text-muted-foreground hover:bg-muted"
              >
                <X aria-hidden="true" className="size-5" />
              </Dialog.Close>
            </div>
            <Dialog.Description className="text-body-sm">{t('screen.job.detail.withdrawBody')}</Dialog.Description>
            <div className="flex flex-wrap justify-between gap-3">
              <Dialog.Close render={<Button variant="secondary" />}>{t('screen.job.detail.withdrawCancel')}</Dialog.Close>
              <Button type="button" onClick={confirm} disabled={withdraw.isPending}>
                {t(withdraw.isPending ? 'screen.job.detail.withdrawing' : 'screen.job.detail.withdrawConfirm')}
              </Button>
            </div>
          </Dialog.Popup>
        </Dialog.Portal>
      </Dialog.Root>
    </>
  )
}
