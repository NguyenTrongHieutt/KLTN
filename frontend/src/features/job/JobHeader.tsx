import type { ReactNode } from 'react'
import { Link, useParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { t } from '@/i18n/t'
import type { Job } from './jobApi'
import { JobStateBadge } from './JobStateBadge'
import { SetupTracker } from './SetupTracker'

/**
 * A Job screen's top (UX-DR17): the breadcrumb, the title with its state, the meta line, an optional
 * status line such as the Crew's confirmations, the step tracker while the Job is Starting (UX-DR23),
 * and the tabs.
 */
export function JobHeader({
  title,
  state,
  meta,
  audience,
  status,
  children,
}: {
  title: string
  state: string
  meta: string
  audience?: 'mentor'
  status?: ReactNode
  children: ReactNode
}) {
  const { jobId } = useParams()
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
          <JobStateBadge state={state as Job['state']} audience={audience} />
        </div>
        <p className="text-caption text-muted-foreground break-words">{meta}</p>
        {status}
      </div>
      {state === 'STARTING' && jobId && <SetupTracker jobId={jobId} />}
      {children}
    </div>
  )
}
