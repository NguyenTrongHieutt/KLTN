import type { ReactNode } from 'react'
import { Link, useParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { t } from '@/i18n/t'
import { BusyBanner } from './BusyBanner'
import { JobRoleLine } from './ByJobRole'
import type { Job } from './jobApi'
import { useJobRole } from './jobRoleApi'
import { JobStateBadge } from './JobStateBadge'
import { audienceOf } from './jobStateText'
import { DriftWarning } from './DriftWarning'
import { SetupTracker } from './SetupTracker'

/**
 * A Job screen's top (UX-DR17, UX-DR87): the breadcrumb, the title with its state in the words of the caller's
 * role in the Job, the meta line, "Bạn là: …", an optional status line such as the Crew's confirmations, the step
 * tracker while the Job is Starting (UX-DR23), the busy banner while a precondition operation holds the Job
 * (UX-DR21), and the tabs.
 */
export function JobHeader({
  title,
  state,
  meta,
  status,
  children,
}: {
  title: string
  state: string
  meta: string
  status?: ReactNode
  children: ReactNode
}) {
  const { jobId } = useParams()
  const role = useJobRole(jobId).data?.role
  return (
    <div className="flex flex-col gap-3">
      <nav aria-label={t('screen.job.detail.breadcrumb')} className="text-caption text-muted-foreground">
        <Link to="/cong-viec" className="text-primary underline-offset-4 hover:underline">
          {t('screen.job.detail.breadcrumb')}
        </Link>{' '}
        / <span className="break-words">{title}</span>
      </nav>
      <div className="flex flex-col gap-1">
        <div className="flex flex-wrap items-center gap-2">
          <PageHeading>{title}</PageHeading>
          <JobStateBadge state={state as Job['state']} audience={role ? audienceOf(role) : undefined} />
        </div>
        <p className="text-caption text-muted-foreground break-words">{meta}</p>
        <JobRoleLine jobId={jobId} state={state} />
        {status}
      </div>
      {state === 'STARTING' && jobId && <SetupTracker jobId={jobId} />}
      {(state === 'ACTIVE' || state === 'CLOSING') && jobId && <BusyBanner jobId={jobId} />}
      {(state === 'ACTIVE' || state === 'CLOSING') && jobId && <DriftWarning jobId={jobId} />}
      {children}
    </div>
  )
}
