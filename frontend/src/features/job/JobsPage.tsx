import { BriefcaseBusiness } from 'lucide-react'
import { Link, useSearchParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { buttonVariants } from '@/components/ui/button-variants'
import { AssignmentPanel, JoinedCard } from '@/features/crew/CrewAssignmentCards'
import { useCrewAssignments } from '@/features/crew/crewApi'
import { useMyApplications, type Application, type ApplicationStatus } from '@/features/matching/applicationApi'
import type { TodoItem } from '@/features/notification/api'
import { useTodos } from '@/features/notification/hooks'
import { TeamIssueSection } from '@/features/teamIssue/TeamIssueSection'
import { t, type MessageKey } from '@/i18n/t'
import { formatDayMonth } from '@/lib/dates'
import { formatVnd } from '@/lib/money'
import { cn } from '@/lib/utils'
import { useJobs, type JobState, type JobSummary } from './jobApi'
import { leftEarly, useMyJobs, type MyJob, type RoleInJob } from './jobRoleApi'
import { JobStateBadge } from './JobStateBadge'

/** CUS-7's segments (UX-DR86), as `?vai-tro=` keeps them: the Jobs the user posts, mentors, and joins. */
type Segment = 'dang' | 'huong-dan' | 'tham-gia'

const SEGMENTS: Segment[] = ['dang', 'huong-dan', 'tham-gia']

const ROLE_OF: Record<Segment, RoleInJob> = { dang: 'CUSTOMER', 'huong-dan': 'MENTOR', 'tham-gia': 'CREW' }

const SEGMENT_OF: Record<RoleInJob, Segment> = { CUSTOMER: 'dang', MENTOR: 'huong-dan', CREW: 'tham-gia' }

/** A bid still in play counts in "Việc tôi hướng dẫn" (UX-DR86): sent, picked, or on hold. */
const PENDING: ApplicationStatus[] = ['SUBMITTED', 'SELECTED', 'ON_HOLD']

/**
 * CUS-7 "Công việc" (`/cong-viec`, FR-9, UX-DR66, UX-DR86, Story 20.6), the one list of every Job the user holds a
 * role in, which MEN-4 and STU-4 merged into: the Toggle Group "Việc tôi đăng" · "Việc tôi hướng dẫn" · "Việc tôi
 * tham gia". Each segment counts its open to-dos, a to-do counting for the role it carries or else for the user's
 * role in its Job, and "Việc tôi hướng dẫn" also counts the pending bids. A segment with nothing is hidden, and so is
 * the control when one is left; "Việc tôi hướng dẫn" shows while the user mentors a Job or has any bid, closed ones
 * included. The URL keeps the segment (`?vai-tro=`); without one, the segment of the most urgent to-do opens, once
 * the to-dos are read. "Việc tôi tham gia" lists the running Jobs and then the past ones, those left included. With
 * no Job and no bid, "Bạn chưa có công việc nào." leads to "Tìm việc". Nothing shows until the Jobs and the bids
 * are read, and a failed read shows its error.
 */
export function JobsPage() {
  usePageTitle(t('screen.job.title'))
  const mine = useMyJobs()
  const bids = useMyApplications()
  const both = {
    isPending: mine.isPending || bids.isPending,
    isError: mine.isError || bids.isError,
    error: mine.error ?? bids.error,
    refetch: () => Promise.all([mine.refetch(), bids.refetch()]),
  }
  return (
    <div className="mx-auto flex max-w-[920px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <PageHeading>{t('screen.job.heading')}</PageHeading>
      <RegionState query={both} skeleton={<Skeleton className="h-48 w-full" />}>
        {mine.data && bids.data && <Segments jobs={mine.data} bids={bids.data} />}
      </RegionState>
    </div>
  )
}

function Segments({ jobs, bids }: { jobs: MyJob[]; bids: Application[] }) {
  const todoQuery = useTodos()
  const todos = todoQuery.data?.items ?? []
  const [params, setParams] = useSearchParams()
  const pending = bids.filter((bid) => PENDING.includes(bid.status))
  const shown = SEGMENTS.filter(
    (segment) => jobs.some((job) => job.role === ROLE_OF[segment]) || (segment === 'huong-dan' && bids.length > 0),
  )
  if (shown.length === 0) {
    return (
      <div className="flex flex-col items-center gap-3 rounded-lg border border-border bg-card p-10 text-center">
        <BriefcaseBusiness aria-hidden="true" className="size-8 text-muted-foreground" />
        <p className="text-label">{t('screen.job.empty')}</p>
        <Link to="/tim-viec" className={buttonVariants()}>
          {t('screen.job.emptyCta')}
        </Link>
      </div>
    )
  }
  // A to-do counts for the role it carries, or else for the user's role in its Job.
  const segmentOf = (todo: TodoItem): Segment | undefined => {
    const role = todo.role ?? jobs.find((job) => job.jobId === todo.jobId)?.role
    return role === undefined ? undefined : SEGMENT_OF[role]
  }
  const counts = Object.fromEntries(
    SEGMENTS.map((segment) => [segment, todos.filter((todo) => segmentOf(todo) === segment).length]),
  ) as Record<Segment, number>
  counts['huong-dan'] += pending.length
  const asked = params.get('vai-tro') as Segment | null
  const chosen = asked !== null && shown.includes(asked)
  if (!chosen && todoQuery.isPending) {
    return <Skeleton className="h-48 w-full" />
  }
  const urgent = todos.map(segmentOf).find((segment) => segment !== undefined && shown.includes(segment))
  const current: Segment = chosen ? asked : (urgent ?? shown[0])
  return (
    <>
      {shown.length > 1 && (
        <div
          role="group"
          aria-label={t('screen.job.segments.label')}
          className="flex flex-wrap gap-1 self-start rounded-md bg-muted p-1"
        >
          {shown.map((segment) => (
            <button
              key={segment}
              type="button"
              aria-pressed={segment === current}
              onClick={() => setParams({ 'vai-tro': segment }, { replace: true })}
              className={cn(
                'inline-flex min-h-tap-min items-center gap-2 rounded px-3 text-label',
                segment === current ? 'bg-card font-semibold text-foreground shadow-sm' : 'text-muted-foreground',
              )}
            >
              {t(`screen.job.segments.${segment}` as MessageKey)}
              {counts[segment] > 0 && (
                <span className="rounded-full bg-destructive px-1.5 text-tag text-destructive-foreground">
                  <span className="sr-only">{t('screen.job.segments.todos', { n: counts[segment] })}</span>
                  <span aria-hidden="true">{counts[segment]}</span>
                </span>
              )}
            </button>
          ))}
        </div>
      )}
      {current === 'dang' && <PostedJobs />}
      {current === 'huong-dan' && <MentoredJobs jobs={jobs.filter((job) => job.role === 'MENTOR')} bids={bids} />}
      {current === 'tham-gia' && <JoinedJobs jobs={jobs.filter((job) => job.role === 'CREW')} />}
    </>
  )
}

type Group = 'Decide' | 'Active' | 'Open' | 'Draft' | 'Ended'

/** CUS-7's groups of the posted Jobs (UX-DR66). */
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
 * "Việc tôi đăng": the Jobs the user posted, grouped as Cần bạn quyết định · Đang thực hiện · Đang tìm người
 * hướng dẫn · Bản nháp · Đã kết thúc, a Draft linking to its form.
 */
function PostedJobs() {
  const jobs = useJobs()
  const items = jobs.data ?? []
  return (
    <RegionState query={jobs} skeleton={<Skeleton className="h-48 w-full" />}>
      {GROUPS.map((group) => (
        <JobGroup key={group} group={group} jobs={items.filter((job) => GROUP_OF[job.state] === group)} />
      ))}
    </RegionState>
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

type BidGroup = 'waiting' | 'held' | 'ended'

const BID_GROUP_OF: Record<ApplicationStatus, BidGroup> = {
  SUBMITTED: 'waiting',
  SELECTED: 'waiting',
  ON_HOLD: 'held',
  CLOSED: 'ended',
  WITHDRAWN: 'ended',
}

/**
 * "Việc tôi hướng dẫn" (formerly MEN-4, UX-DR78): the Jobs the user mentors, in the Mentor's words, and their bids
 * by where they stand (waiting, on hold, ended), each linking to its Job Post.
 */
function MentoredJobs({ jobs, bids }: { jobs: MyJob[]; bids: Application[] }) {
  return (
    <>
      <section aria-labelledby="mentor-jobs" className="flex flex-col gap-2">
        <h2 id="mentor-jobs" className="text-h2">
          {t('screen.matching.mentor.jobs')}
        </h2>
        {jobs.length === 0 ? (
          <p className="text-body-sm text-muted-foreground">{t('screen.matching.mentor.noJobs')}</p>
        ) : (
          <JobRows jobs={jobs} />
        )}
      </section>
      {(['waiting', 'held', 'ended'] as const).map((group) => (
        <BidGroupSection key={group} group={group} items={bids.filter((bid) => BID_GROUP_OF[bid.status] === group)} />
      ))}
    </>
  )
}

function BidGroupSection({ group, items }: { group: BidGroup; items: Application[] }) {
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

/**
 * "Việc tôi tham gia" (formerly STU-4, FR-75, AD-41, UX-DR60): a pick waiting for the user shows the Crew
 * assignment panel and a confirmed one its card; then the past Jobs, those left included, a Job left before it
 * ended marked "· đã rời"; then the internal complaints (FR-79).
 */
function JoinedJobs({ jobs }: { jobs: MyJob[] }) {
  const assignments = useCrewAssignments()
  // Story 11.3: an active member asked to confirm a waiting Crew change sees its version here too.
  const waiting = assignments.data?.filter((assignment) => assignment.status === 'ASSIGNED' || assignment.changeWaiting) ?? []
  const joined = assignments.data?.filter((assignment) => assignment.status !== 'ASSIGNED') ?? []
  const past = jobs.filter((job) => job.crewKind === 'FORMER')
  return (
    <>
      <RegionState query={assignments} skeleton={<Skeleton className="h-48 w-full" />}>
        {waiting.map((assignment) => (
          <AssignmentPanel key={`${assignment.jobId}-${assignment.versionNo}`} assignment={assignment} />
        ))}
        {joined.length > 0 && (
          <section className="flex flex-col gap-2.5">
            <h2 className="text-h2">{t('screen.crew.student.activeTitle')}</h2>
            {joined.map((assignment) => (
              <JoinedCard key={assignment.jobId} assignment={assignment} />
            ))}
          </section>
        )}
      </RegionState>
      {past.length > 0 && (
        <section aria-labelledby="jobs-past" className="flex flex-col gap-2">
          <h2 id="jobs-past" className="text-h2">
            {t('screen.job.segments.pastTitle')}
          </h2>
          <JobRows jobs={past} />
        </section>
      )}
      <TeamIssueSection />
    </>
  )
}

/** Jobs as rows to their JOB-1, in the Mentor's and the Crew's words; one the user left marked "· đã rời". */
function JobRows({ jobs }: { jobs: MyJob[] }) {
  return (
    <ul className="flex flex-col divide-y divide-border rounded-lg border border-border bg-card">
      {jobs.map((job) => (
        <li key={job.jobId}>
          <Link
            to={`/cong-viec/${job.jobId}`}
            className="flex flex-wrap items-center gap-3 px-4 py-3 text-inherit no-underline hover:bg-muted/50"
          >
            <span className="flex min-w-60 grow flex-col gap-0.5">
              <span className="text-label font-semibold break-words">
                {leftEarly(job, job.state) ? t('screen.job.segments.left', { job: job.title }) : job.title}
              </span>
              <span className="text-caption text-muted-foreground break-words">{job.productName}</span>
            </span>
            <JobStateBadge state={job.state} audience="mentor" />
          </Link>
        </li>
      ))}
    </ul>
  )
}
