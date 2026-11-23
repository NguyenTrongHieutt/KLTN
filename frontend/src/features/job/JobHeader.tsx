import type { ReactNode } from 'react'
import { Link, useParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { t } from '@/i18n/t'
import { BusyBanner } from './BusyBanner'
import { NoCrewBanner } from '@/features/crew/NoCrew'
import { JobPhaseBadge, JobRoleLine } from './ByJobRole'
import type { Job } from './jobApi'
import { useJobRole } from './jobRoleApi'
import { JobStateBadge } from './JobStateBadge'
import { audienceOf } from './jobStateText'
import { DriftWarning } from './DriftWarning'
import { SetupTracker } from './SetupTracker'
import { StageStrip } from './StageStrip'

/**
 * A Job tab's top (UX-DR17, UX-DR87): the breadcrumb, the title with its state in the words of the caller's
 * role in the Job and its phase (X6), the meta line with "Bạn là: …" beside it, an optional status line such as the Crew's
 * confirmations, the Mentor's No-Crew banner (UX-DR104), the step tracker while the Job is Starting (UX-DR23), the busy
 * banner while a precondition operation holds the Job (UX-DR21), the Drift Warning from the Customer's confirmation on
 * (Starting, J7 or not, Active and Closing; Story 24.6), and the tabs. The stage strip is the compact one: the named
 * stages are Tổng quan's.
 */
export function JobHeader({
  title,
  state,
  meta,
  status,
  actions,
  children,
}: {
  title: string
  state: string
  meta: string
  status?: ReactNode
  /** The page's own actions, on the right of the title from 768 px (for example "Rút tin"). */
  actions?: ReactNode
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
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex min-w-0 flex-col gap-1">
          <div className="flex flex-wrap items-center gap-2">
            <PageHeading className="break-words">{title}</PageHeading>
            <JobStateBadge state={state as Job['state']} audience={role ? audienceOf(role) : undefined} />
            <JobPhaseBadge jobId={jobId} />
          </div>
          <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
            <p className="text-caption text-muted-foreground break-words">{meta}</p>
            <JobRoleLine jobId={jobId} />
          </div>
          {status}
        </div>
        {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
      </div>
      {/* Tổng quan shows the seven named stages; the other tabs keep the one line and the seven segments. */}
      <StageStrip state={state as Job['state']} compact />
      {jobId && <NoCrewBanner jobId={jobId} />}
      {state === 'STARTING' && jobId && <SetupTracker jobId={jobId} />}
      {(state === 'ACTIVE' || state === 'CLOSING') && jobId && <BusyBanner jobId={jobId} />}
      {(state === 'STARTING' || state === 'ACTIVE' || state === 'CLOSING') && jobId && <DriftWarning jobId={jobId} />}
      {children}
    </div>
  )
}
