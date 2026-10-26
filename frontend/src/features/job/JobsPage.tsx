import { FileText } from 'lucide-react'
import { Link } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { buttonVariants } from '@/components/ui/button-variants'
import { useSession } from '@/features/identity/useSession'
import { StudentJobsPage } from '@/features/crew/StudentJobsPage'
import { MentorJobsPage } from '@/features/matching/MentorJobsPage'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t, type MessageKey } from '@/i18n/t'
import { formatDayMonth } from '@/lib/dates'
import { useJobs, type JobState, type JobSummary } from './jobApi'
import { JobStateBadge } from './JobStateBadge'

type Group = 'Decide' | 'Active' | 'Open' | 'Draft' | 'Ended'

/** CUS-7's groups (UX-DR66); deciding states come with the lifecycle of Story 3.2 on. */
const GROUPS: Group[] = ['Decide', 'Active', 'Open', 'Draft', 'Ended']

const GROUP_OF: Record<JobState, Group> = {
  DRAFT: 'Draft',
  OPEN: 'Open',
  SELECTING: 'Decide',
  AGREEMENT: 'Decide',
  STARTING: 'Active',
  ACTIVE: 'Active',
  CLOSING: 'Active',
  CLOSED: 'Ended',
  CANCELLED: 'Ended',
}

/**
 * CUS-7 (`/cong-viec`, FR-9, UX-DR66): the Customer's Jobs grouped as Cần bạn quyết định · Đang thực
 * hiện · Đang tìm người hướng dẫn · Bản nháp · Đã kết thúc; with none, the way to post one from a
 * Product. A Mentor's `/cong-viec` is MEN-4, and a Student's is STU-4.
 */
export function JobsPage() {
  usePageTitle(t('screen.job.title'))
  const account = useSession().data?.account
  if (account?.role === 'MENTOR') {
    return <MentorJobsPage />
  }
  if (account?.role === 'STUDENT') {
    return <StudentJobsPage />
  }
  if (account !== undefined && account.role !== 'CUSTOMER') {
    return <NotFoundScreen />
  }
  return <JobList />
}

function JobList() {
  const jobs = useJobs()
  const items = jobs.data ?? []
  return (
    <div className="mx-auto flex max-w-[880px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <PageHeading>{t('screen.job.heading')}</PageHeading>
      <RegionState query={jobs} skeleton={<Skeleton className="h-48 w-full" />}>
        {items.length === 0 ? (
          <div className="flex flex-col items-center gap-3 rounded-lg border border-border bg-card p-10 text-center">
            <FileText aria-hidden="true" className="size-8 text-muted-foreground" />
            <p className="text-label">{t('screen.job.empty')}</p>
            <Link to="/san-pham" className={buttonVariants()}>
              {t('screen.job.emptyCta')}
            </Link>
          </div>
        ) : (
          GROUPS.map((group) => (
            <JobGroup key={group} group={group} jobs={items.filter((job) => GROUP_OF[job.state] === group)} />
          ))
        )}
      </RegionState>
    </div>
  )
}

function JobGroup({ group, jobs }: { group: Group; jobs: JobSummary[] }) {
  const id = `jobs-${group}`
  return (
    <section aria-labelledby={id} className="flex flex-col gap-2.5">
      <h2 id={id} className="text-h2">
        {t(`screen.job.group${group}` as MessageKey)}{' '}
        <span className="font-normal text-muted-foreground">{t('screen.job.groupCount', { n: jobs.length })}</span>
      </h2>
      {jobs.length === 0 ? (
        <p className="text-body-sm text-muted-foreground">{t('screen.job.groupEmpty')}</p>
      ) : (
        <ul className="flex flex-col divide-y divide-border rounded-lg border border-border bg-card">
          {jobs.map((job) => (
            <li key={job.id}>
              <Link
                to={job.state === 'DRAFT' ? `/cong-viec/${job.id}/sua` : `/cong-viec/${job.id}`}
                className="flex flex-wrap items-center gap-3 px-4 py-3 text-inherit no-underline hover:bg-muted/50"
              >
                <span className="flex min-w-60 grow flex-col gap-0.5">
                  <span className="text-label font-semibold break-words">{job.title}</span>
                  <span className="text-caption text-muted-foreground break-words">
                    {[
                      job.productName,
                      job.jobTypes.map((type) => t(`term.jobType${type}.label` as MessageKey)).join(', '),
                      t('screen.job.editedOn', { date: formatDayMonth(job.updatedAt) }),
                    ]
                      .filter((part) => part !== '')
                      .join(' · ')}
                  </span>
                </span>
                <JobStateBadge state={job.state} />
              </Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
