import { Link } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { t, type MessageKey } from '@/i18n/t'
import { formatDayMonth } from '@/lib/dates'
import { formatVnd } from '@/lib/money'
import { JobStateBadge } from '@/features/job/JobStateBadge'
import { useMyApplications, type Application, type ApplicationStatus } from './applicationApi'
import { useMentorJobs } from './mentorJobApi'

type Group = 'waiting' | 'held' | 'ended'

const GROUP_OF: Record<ApplicationStatus, Group> = {
  SUBMITTED: 'waiting',
  SELECTED: 'waiting',
  ON_HOLD: 'held',
  CLOSED: 'ended',
  WITHDRAWN: 'ended',
}

/**
 * MEN-4 (`/cong-viec` for Mentors, UX-DR78): the Mentor's Jobs by state (none before a selection,
 * Story 3.7) and their Applications: waiting, on hold, and ended, each linking to its Job Post.
 */
export function MentorJobsPage() {
  const applications = useMyApplications()
  const jobs = useMentorJobs()
  const items = applications.data ?? []
  const mine = jobs.data ?? []
  return (
    <div className="mx-auto flex max-w-[880px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <PageHeading>{t('screen.job.heading')}</PageHeading>
      <section aria-labelledby="mentor-jobs" className="flex flex-col gap-2">
        <h2 id="mentor-jobs" className="text-h2">
          {t('screen.matching.mentor.jobs')}
        </h2>
        {mine.length === 0 ? (
          <p className="text-body-sm text-muted-foreground">{t('screen.matching.mentor.noJobs')}</p>
        ) : (
          <ul className="flex flex-col divide-y divide-border rounded-lg border border-border bg-card">
            {mine.map((job) => (
              <li key={job.id}>
                <Link
                  to={`/cong-viec/${job.id}`}
                  className="flex flex-wrap items-center gap-3 px-4 py-3 text-inherit no-underline hover:bg-muted/50"
                >
                  <span className="flex min-w-60 grow flex-col gap-0.5">
                    <span className="text-label font-semibold break-words">{job.title}</span>
                    <span className="text-caption text-muted-foreground break-words">{job.productName}</span>
                  </span>
                  <JobStateBadge state={job.state} audience="mentor" />
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>
      <RegionState query={applications} skeleton={<Skeleton className="h-48 w-full" />}>
        {items.length === 0 ? (
          <div className="flex flex-col items-start gap-2 rounded-lg border border-border bg-card p-6">
            <p className="text-label">{t('screen.matching.mentor.noApplications')}</p>
            <Link to="/tim-viec" className="text-primary underline-offset-4 hover:underline">
              {t('screen.matching.mentor.findWork')}
            </Link>
          </div>
        ) : (
          (['waiting', 'held', 'ended'] as const).map((group) => (
            <ApplicationGroup key={group} group={group} items={items.filter((item) => GROUP_OF[item.status] === group)} />
          ))
        )}
      </RegionState>
    </div>
  )
}

function ApplicationGroup({ group, items }: { group: Group; items: Application[] }) {
  if (items.length === 0) {
    return null
  }
  const id = `applications-${group}`
  return (
    <section aria-labelledby={id} className="flex flex-col gap-2">
      <h2 id={id} className="text-h2">
        {t(`screen.matching.mentor.group_${group}` as MessageKey)}{' '}
        <span className="font-normal text-muted-foreground">· {items.length}</span>
      </h2>
      <ul className="flex flex-col divide-y divide-border rounded-lg border border-border bg-card">
        {items.map((application) => (
          <li key={application.id}>
            <Link
              to={`/tim-viec/${application.jobId}`}
              className="flex flex-wrap items-center gap-3 px-4 py-3 text-inherit no-underline hover:bg-muted/50"
            >
              <span className="flex min-w-60 grow flex-col gap-0.5">
                <span className="text-label font-semibold break-words">{application.jobTitle}</span>
                <span className="text-caption text-muted-foreground">
                  {formatVnd(application.priceVnd)} {t('screen.matching.test')} ·{' '}
                  {t('screen.matching.days', { n: application.timelineDays })} ·{' '}
                  {t('screen.matching.list.sent', { date: formatDayMonth(application.submittedAt) })}
                </span>
              </span>
              <span className="rounded-full bg-muted px-2 py-0.5 text-caption font-semibold">
                {t(
                  application.endReason
                    ? (`screen.matching.mentor.end_${application.endReason}` as MessageKey)
                    : (`screen.matching.mentor.status_${application.status}` as MessageKey),
                )}
              </span>
            </Link>
          </li>
        ))}
      </ul>
    </section>
  )
}
