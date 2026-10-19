import { Link, Navigate, useParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { useSession } from '@/features/identity/useSession'
import { useJob, type Job } from '@/features/job/jobApi'
import { JobStateBadge } from '@/features/job/JobStateBadge'
import { JobTabs } from '@/features/job/JobTabs'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t } from '@/i18n/t'
import { formatDayMonth } from '@/lib/dates'
import { formatVnd } from '@/lib/money'
import { isNotFound } from '@/lib/problemMessage'
import { useJobApplications, type JobApplication } from './applicationApi'

/**
 * CUS-8 "Đơn ứng tuyển" (`/cong-viec/{jobId}/ung-tuyen`, FR-13, UX-DR66): the open Applications to
 * the Customer's Job, newest first, each with the Mentor (linked to PUB-5), the price as test money,
 * the days, and the approach. Choosing one arrives with Story 3.7.
 */
export function JobApplicationsPage() {
  const { jobId } = useParams()
  const job = useJob(jobId)
  const role = useSession().data?.account?.role
  usePageTitle(job.data ? t('screen.matching.list.title', { job: job.data.title }) : t('screen.job.title'))
  if ((role !== undefined && role !== 'CUSTOMER') || (job.isError && isNotFound(job.error))) {
    return <NotFoundScreen />
  }
  if (job.data?.state === 'DRAFT') {
    return <Navigate to={`/cong-viec/${job.data.id}/sua`} replace />
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
          <ul className="flex flex-col gap-3">
            {items.map((application) => (
              <ApplicationCard key={application.id} application={application} />
            ))}
          </ul>
        )}
      </RegionState>
      <p className="text-caption text-muted-foreground">{t('screen.matching.list.note')}</p>
    </>
  )
}

function ApplicationCard({ application }: { application: JobApplication }) {
  const mentor = application.mentor
  return (
    <li className="flex flex-col gap-2 rounded-lg border border-border bg-card p-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        {mentor.githubLogin ? (
          <Link to={`/mentor/${mentor.githubLogin}`} className="text-label font-semibold break-words text-primary underline-offset-4 hover:underline">
            {mentor.displayName}
          </Link>
        ) : (
          <span className="text-label font-semibold break-words">{mentor.displayName}</span>
        )}
        <span className="text-caption text-muted-foreground">
          {t('screen.matching.list.sent', { date: formatDayMonth(application.submittedAt) })}
        </span>
      </div>
      <p className="text-caption text-muted-foreground">
        {t('screen.matching.list.mentor', { years: mentor.yearsOfExperience, n: mentor.teamSize })}
      </p>
      <p className="text-body-sm">
        <span className="font-semibold">{formatVnd(application.priceVnd)}</span>{' '}
        <span className="rounded bg-warning-soft px-1 text-caption font-semibold text-warning">{t('screen.matching.test')}</span>
        {' · '}
        {t('screen.matching.days', { n: application.timelineDays })}
      </p>
      <p className="text-body-sm break-words whitespace-pre-line">{application.approach}</p>
    </li>
  )
}
