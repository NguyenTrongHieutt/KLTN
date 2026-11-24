import type { ReactNode } from 'react'
import { Card } from '@/components/ui/card'
import { DescriptionList } from '@/components/ui/description-list'
import type { JobState } from '@/features/job/jobApi'
import { useJobRole } from '@/features/job/jobRoleApi'
import { hasStarted } from '@/features/job/jobState'
import { t } from '@/i18n/t'
import { formatCalendarDayMonth } from '@/lib/dates'
import type { ProgressAudience } from './ProgressOverview'
import { useJobProgress, type JobProgress } from './progressApi'

/** Where the side column sticks under the sticky top bar from 1024 px. */
const STICKY = 'lg:sticky lg:top-[calc(var(--spacing-header-height)+1.5rem)] lg:self-start'

/**
 * Every Job tab's body (UX-DR17): the tab's content in the main column and, from 1024 px, the side column
 * "Thông tin công việc" once the Job has started, so the main column keeps one width on every tab.
 */
export function JobTabBody({ jobId, state, children }: { jobId: string; state?: JobState; children: ReactNode }) {
  return (
    <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_280px]">
      <div className="flex min-w-0 flex-col gap-6">{children}</div>
      {state !== undefined && hasStarted(state) && <JobInfoAside jobId={jobId} />}
    </div>
  )
}

/** The side column of a Job tab, read from the Job's progress (shared with Tổng quan, so it is cached). */
function JobInfoAside({ jobId }: { jobId: string }) {
  const progress = useJobProgress(jobId)
  const role = useJobRole(jobId).data?.role
  if (!progress.data) {
    return null
  }
  return <JobInfoCard progress={progress.data} audience={role === 'MENTOR' ? 'mentor' : 'customer'} />
}

/**
 * "Thông tin công việc" (UX-DR19): the next due date and the people (the Mentor, and for the Mentor the Crew still on
 * it). The Product is in the Job's header on every tab, so it is not said here a second time (2026-11-24). It sticks
 * under the top bar while the main column scrolls.
 */
export function JobInfoCard({ progress, audience }: { progress: JobProgress; audience: ProgressAudience }) {
  const nextDue = progress.milestones.find(
    (milestone) => milestone.dueOn && !['ACCEPTED', 'CANCELLED', 'SETTLED'].includes(milestone.state),
  )
  return (
    <aside aria-label={t('screen.progress.aside')} className={STICKY}>
      <Card as="section" className="flex flex-col gap-4">
        <h2 className="text-h3">{t('screen.progress.aside')}</h2>
        <DescriptionList
          stacked
          items={[
            {
              term: t('screen.progress.deadline'),
              value: nextDue?.dueOn
                ? t('screen.progress.deadlineLine', { n: nextDue.number, date: formatCalendarDayMonth(nextDue.dueOn) })
                : t('screen.progress.noDeadline'),
            },
            {
              term: t('screen.progress.people'),
              value: (
                <span className="flex flex-col">
                  <span>{t('screen.progress.mentor', { name: progress.mentorName })}</span>
                  {audience === 'mentor' &&
                    progress.crew
                      .filter((member) => member.state !== 'RELEASED')
                      .map((member) => <span key={member.accountId}>{t('screen.progress.member', { name: member.name })}</span>)}
                </span>
              ),
            },
          ]}
        />
      </Card>
    </aside>
  )
}
