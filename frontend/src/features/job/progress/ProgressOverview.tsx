import { ExternalLink, ShieldAlert } from 'lucide-react'
import { Link } from 'react-router'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { TestTag } from '@/components/TestTag'
import { buttonVariants } from '@/components/ui/button-variants'
import { FollowUpSection } from '@/features/delivery/FollowUpSection'
import { DisputeSection } from '@/features/dispute/DisputeSection'
import { DriftWarning } from '@/features/job/DriftWarning'
import { CancellationSection } from '@/features/job/cancellation/CancellationSection'
import { ClosureSection } from '@/features/job/closure/ClosureSection'
import { milestoneStateText, milestoneTone } from '@/features/job/jobStateText'
import { ownGitHubLink, ownLinkSettled, ownRow, useSetup } from '@/features/job/setupApi'
import { useTodos } from '@/features/notification/hooks'
import { todoText } from '@/features/notification/texts'
import { JobPortfolioSection } from '@/features/reputation/JobPortfolioSection'
import { RatingSection } from '@/features/reputation/RatingSection'
import { JobTeamIssues } from '@/features/teamIssue/JobTeamIssues'
import { t, type MessageKey } from '@/i18n/t'
import { formatCalendarDayMonth, formatDayMonth } from '@/lib/dates'
import { formatVnd } from '@/lib/money'
import { isNotFound } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { useJobProgress, type JobProgress, type MilestoneProgress } from './progressApi'

export type ProgressAudience = 'customer' | 'mentor' | 'crew'

/**
 * JOB-1 from Active on (FR-28, AD-33, UX-DR14, UX-DR19, UX-DR32, UX-DR68, UX-DR71): each Milestone's card
 * with its status text, price, and due date; the Job's most urgent to-do; and a side column with the next due
 * date, the people, and the Product. No card carries a progress figure: the platform no longer tracks the
 * team's tasks (Story 24.2a), and the per-criterion detail the Customer decides on is in each Delivery Report.
 * The Mentor also sees the Crew with each member's state. On an Assessment Job the Mentor and the Crew get N-12
 * and, under it, their own link to the private read-only copy they may read (FR-10, UX-DR8, R6, Story 23.11b).
 * It polls every 30 s. A cancelled Job shows it when the Job answers at all (`optional`: nothing when it does
 * not).
 */
export function ProgressOverview({
  jobId,
  audience,
  optional = false,
}: {
  jobId: string
  audience: ProgressAudience
  optional?: boolean
}) {
  const progress = useJobProgress(jobId)
  if (optional && progress.isError && isNotFound(progress.error)) {
    return null
  }
  return (
    <RegionState query={progress} skeleton={<Skeleton className="h-96 w-full" />}>
      {progress.data && <Content jobId={jobId} audience={audience} progress={progress.data} />}
    </RegionState>
  )
}

function Content({ jobId, audience, progress }: { jobId: string; audience: ProgressAudience; progress: JobProgress }) {
  const nextToFund = progress.milestones.find((milestone) => milestone.state === 'PLANNED')?.milestoneId
  const nextDue = progress.milestones.find(
    (milestone) => milestone.dueOn && !['ACCEPTED', 'CANCELLED', 'SETTLED'].includes(milestone.state),
  )
  return (
    <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_280px]">
      <div className="flex min-w-0 flex-col gap-6">
        {(audience === 'crew' || (audience === 'mentor' && progress.assessment)) && (
          <p role="note" className="flex gap-2 rounded-md border-l-4 border-warning bg-warning-soft p-3 text-body-sm">
            <ShieldAlert aria-hidden="true" className="size-5 shrink-0 text-warning" />
            {t('screen.progress.n12')}
          </p>
        )}
        {audience !== 'customer' && progress.assessment && <AssessmentCopyLink jobId={jobId} />}
        {!progress.assessment && <DriftWarning jobId={jobId} />}
        <DisputeSection jobId={jobId} />
        {audience === 'mentor' && <JobTeamIssues jobId={jobId} />}
        <JobNextAction jobId={jobId} />
        <ClosureSection jobId={jobId} />
        <CancellationSection jobId={jobId} />
        {progress.state === 'CLOSED' && <RatingSection jobId={jobId} />}
        {progress.state === 'CLOSED' && <JobPortfolioSection jobId={jobId} />}
        {progress.assessment && <FollowUpSection jobId={jobId} />}
        <section aria-labelledby="progress-milestones" className="flex flex-col gap-3">
          <h2 id="progress-milestones" className="text-h2">
            {t('screen.progress.milestones')}
          </h2>
          <ul className="flex flex-col gap-3">
            {progress.milestones.map((milestone) => (
              <MilestoneCard
                key={milestone.milestoneId}
                jobId={jobId}
                milestone={milestone}
                audience={audience}
                nextToFund={milestone.milestoneId === nextToFund}
              />
            ))}
          </ul>
        </section>
        {audience === 'mentor' && (
          <section aria-labelledby="progress-crew" className="flex flex-col gap-3">
            <h2 id="progress-crew" className="text-h2">
              {t('screen.progress.crew')}
            </h2>
            {progress.crew.length === 0 ? (
              <p className="text-body-sm text-muted-foreground">{t('screen.progress.noCrew')}</p>
            ) : (
              <ul className="flex flex-col gap-3">
                {progress.crew.map((member) => (
                  <li
                    key={member.accountId}
                    className="flex flex-wrap items-center gap-2 rounded-lg border border-border bg-card p-4"
                  >
                    <p className="text-label font-semibold">{member.name}</p>
                    <span className={cn('rounded-full px-2 py-0.5 text-caption font-semibold', crewStateTone(member.state))}>
                      {t(`screen.progress.crewState.${member.state}` as MessageKey)}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </section>
        )}
      </div>
      <aside aria-label={t('screen.progress.aside')} className="flex flex-col gap-4">
        <section className="flex flex-col gap-1 rounded-lg border border-border bg-card p-4 text-body-sm">
          <h3 className="text-h3">{t('screen.progress.deadline')}</h3>
          <p>
            {nextDue?.dueOn
              ? t('screen.progress.deadlineLine', { n: nextDue.number, date: formatCalendarDayMonth(nextDue.dueOn) })
              : t('screen.progress.noDeadline')}
          </p>
        </section>
        <section className="flex flex-col gap-1 rounded-lg border border-border bg-card p-4 text-body-sm">
          <h3 className="text-h3">{t('screen.progress.people')}</h3>
          <p>{t('screen.progress.mentor', { name: progress.mentorName })}</p>
          {audience === 'mentor' &&
            progress.crew
              .filter((member) => member.state !== 'RELEASED')
              .map((member) => <p key={member.accountId}>{t('screen.progress.member', { name: member.name })}</p>)}
        </section>
        <section className="flex flex-col gap-1 rounded-lg border border-border bg-card p-4 text-body-sm">
          <h3 className="text-h3">{t('screen.progress.product')}</h3>
          <p className="break-words">{progress.productName}</p>
        </section>
      </aside>
    </div>
  )
}

/**
 * The Mentor's and the Crew's own way into an Assessment Job's private read-only copy (R6, Story 23.11b), under the
 * N-12 notice: their pending invitation on GitHub while it waits for them, the copy itself once they accepted. The
 * link is each viewer's own row of the Setup, which the server builds and nobody else's row carries; while the
 * invitation is still being sent the page says so, and with no row of their own -- before the copy, or once it is
 * gone -- there is no link at all. Nothing here names the Customer's Source Repository: a private Source would not
 * serve the team.
 */
function AssessmentCopyLink({ jobId }: { jobId: string }) {
  const setup = useSetup(jobId, ownLinkSettled)
  const own = setup.data ? ownGitHubLink(setup.data) : undefined
  if (!own) {
    const inviting = setup.data !== undefined && ownRow(setup.data)?.state === 'INVITING'
    return inviting ? (
      <p className="text-body-sm text-muted-foreground">{t('screen.progress.assessmentCopyInviting')}</p>
    ) : null
  }
  return (
    <a
      href={own.url}
      target="_blank"
      rel="noopener noreferrer nofollow"
      className="flex w-fit items-center gap-2 text-body-sm text-primary underline-offset-4 hover:underline"
    >
      <ExternalLink aria-hidden="true" className="size-4" />
      {t(own.accepted ? 'screen.progress.assessmentCopy' : 'screen.progress.assessmentCopyInvitation')}
      <span className="sr-only"> ({t('screen.board.attachments.external')})</span>
    </a>
  )
}

/** FR-48: the Customer's options on an overdue Milestone; nothing happens by itself. */
function OverdueOptions({ jobId }: { jobId: string }) {
  const link = 'text-primary underline-offset-4 hover:underline'
  return (
    <p className="text-body-sm">
      {t('screen.progress.overdueOptions')} {t('screen.progress.overdueWait')},{' '}
      <Link to={`/cong-viec/${jobId}/thoa-thuan#doi-han`} className={link}>
        {t('screen.progress.overdueNewDate')}
      </Link>
      ,{' '}
      <a href="#huy-cong-viec" className={link}>
        {t('screen.progress.overdueCancel')}
      </a>
      ,{' '}
      <a href="#tranh-chap" className={link}>
        {t('screen.progress.overdueDispute')}
      </a>
      .
    </p>
  )
}

function MilestoneCard({
  jobId,
  milestone,
  audience,
  nextToFund,
}: {
  jobId: string
  milestone: MilestoneProgress
  audience: ProgressAudience
  nextToFund: boolean
}) {
  const textAudience = audience === 'customer' ? 'customer' : 'mentor'
  const text = milestoneStateText(milestone.state, textAudience, {
    nextToFund,
    dueOn: milestone.dueOn,
    roundsLeft: milestone.roundsLeft,
    revisionCriteria: milestone.revisionCriteria,
    autoAccepted: milestone.autoAccepted,
    refunded: milestone.refunded,
  })
  return (
    <li className="flex flex-col gap-3 rounded-lg border border-border bg-card p-5">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <h3 className="text-h3 break-words">{t('screen.progress.milestone', { n: milestone.number, title: milestone.title })}</h3>
        <span className="flex flex-wrap gap-2">
          {milestone.overdueSince && (
            <span className="rounded-full bg-destructive-soft px-2 py-0.5 text-caption font-semibold text-destructive">
              {t('screen.progress.overdue', { date: formatDayMonth(milestone.overdueSince) })}
            </span>
          )}
          <span className={cn('rounded-full px-2 py-0.5 text-caption font-semibold', milestoneTone(milestone.state, textAudience, nextToFund))}>
            {text}
          </span>
        </span>
      </div>
      <p className="flex flex-wrap gap-x-5 gap-y-1 text-body-sm tabular-nums">
        {milestone.priceVnd != null && (
          <span>
            {formatVnd(milestone.priceVnd)} <TestTag />
          </span>
        )}
        {milestone.dueOn && <span>{t('screen.progress.due', { date: formatCalendarDayMonth(milestone.dueOn) })}</span>}
      </p>
      {milestone.overdueSince && audience === 'customer' && <OverdueOptions jobId={jobId} />}
    </li>
  )
}

/** AD-41: an active member reads as success, and the rest muted. */
function crewStateTone(state: string): string {
  return state === 'ACTIVE' ? 'bg-success-soft text-success' : 'bg-muted text-muted-foreground'
}

/** The Job's most urgent to-do (UX-DR14), when the viewer has one on this Job. */
function JobNextAction({ jobId }: { jobId: string }) {
  const todos = useTodos(jobId)
  const first = todos.data?.items[0]
  if (!first || todos.isError) {
    return null
  }
  return (
    <section
      aria-label={t('screen.progress.nextAction')}
      className="flex flex-wrap items-center justify-between gap-3 rounded-lg border-l-4 border-primary bg-primary-soft p-4"
    >
      <span className="text-label">{todoText(first)}</span>
      <Link to={first.link} className={buttonVariants({ size: 'compact' })}>
        {t('screen.shared.todo.open')}
      </Link>
    </section>
  )
}
