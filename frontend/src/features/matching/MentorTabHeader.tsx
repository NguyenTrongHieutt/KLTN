import type { ComponentProps } from 'react'
import { Link } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { JobPhaseBadge, JobRoleLine } from '@/features/job/ByJobRole'
import { jobHeaderCard } from '@/features/job/jobFrame'
import { mentorMeta } from '@/features/job/jobMeta'
import { JobStateBadge } from '@/features/job/JobStateBadge'
import { StageStrip } from '@/features/job/StageStrip'
import { t } from '@/i18n/t'
import { MentorJobTabs } from './MentorJobTabs'
import type { MentorJob } from './mentorJobApi'

/**
 * The top of a Mentor's Job tab that is not built on `JobHeader` ("Tuyển thành viên", "Phân chia tiền"): the same
 * card as the Job's other tabs, with the breadcrumb, the Job's title and state, the meta line with "Bạn là: …", the
 * compact stage strip and the tabs. The tab's own name is said by the tab alone, where it stood a second time as
 * the page's heading before 2026-11-24.
 */
export function MentorTabHeader({
  job,
  current,
}: {
  job: MentorJob
  current: ComponentProps<typeof MentorJobTabs>['current']
}) {
  return (
    <div className={jobHeaderCard}>
      <nav aria-label={t('screen.job.detail.breadcrumb')} className="text-caption text-muted-foreground">
        <Link to="/cong-viec" className="text-primary underline-offset-4 hover:underline">
          {t('screen.job.detail.breadcrumb')}
        </Link>{' '}
        / <span className="break-words">{job.title}</span>
      </nav>
      <div className="flex flex-wrap items-center gap-2">
        <PageHeading className="break-words">{job.title}</PageHeading>
        <JobStateBadge state={job.state} audience="mentor" />
        <JobPhaseBadge jobId={job.id} />
      </div>
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <p className="text-caption break-words text-muted-foreground">{mentorMeta(job)}</p>
        <JobRoleLine jobId={job.id} />
      </div>
      <StageStrip state={job.state} compact />
      <MentorJobTabs jobId={job.id} state={job.state} current={current} payment={job.agreementConfirmed} />
    </div>
  )
}
