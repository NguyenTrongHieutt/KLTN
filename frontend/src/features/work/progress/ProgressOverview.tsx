import { ExternalLink, ShieldAlert } from 'lucide-react'
import { Link } from 'react-router'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { TestTag } from '@/components/TestTag'
import { buttonVariants } from '@/components/ui/button-variants'
import { ClosureSection } from '@/features/job/closure/ClosureSection'
import { milestoneStateText, milestoneTone } from '@/features/job/jobStateText'
import { useTodos } from '@/features/notification/hooks'
import { todoText } from '@/features/notification/texts'
import { JobPortfolioSection } from '@/features/reputation/JobPortfolioSection'
import { RatingSection } from '@/features/reputation/RatingSection'
import { t, type MessageKey } from '@/i18n/t'
import { formatCalendarDayMonth } from '@/lib/dates'
import { formatVnd } from '@/lib/money'
import { cn } from '@/lib/utils'
import { useJobProgress, type JobProgress, type MilestoneProgress } from './progressApi'

export type ProgressAudience = 'customer' | 'mentor' | 'crew'

/**
 * JOB-1 from Active on (FR-28, AD-33, AD-39, UX-DR14, UX-DR19, UX-DR32, UX-DR68, UX-DR71): each
 * Milestone's card with its status text, price, progress bar, and due date; the Job's most urgent to-do;
 * the customer-visible Pages; and a side column with the next due date, the people, and the Product.
 * The Mentor also sees the Crew with each member's open Work Items; a Crew member sees their own and
 * N-12. On an Assessment Job the Mentor and the Crew get the public Source Repository they read and N-12
 * (FR-10, UX-DR8). The Customer never sees a Work Item. It polls every 30 s.
 */
export function ProgressOverview({ jobId, audience }: { jobId: string; audience: ProgressAudience }) {
  const progress = useJobProgress(jobId)
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
            {t('screen.docs.n12')}
          </p>
        )}
        {audience !== 'customer' && progress.sourceRepositoryUrl?.startsWith('https://github.com/') && (
          <a
            href={progress.sourceRepositoryUrl}
            target="_blank"
            rel="noopener noreferrer nofollow"
            className="flex w-fit items-center gap-2 text-body-sm text-primary underline-offset-4 hover:underline"
          >
            <ExternalLink aria-hidden="true" className="size-4" />
            {t('screen.progress.sourceRepository')}
            <span className="sr-only"> ({t('screen.board.attachments.external')})</span>
          </a>
        )}
        <JobNextAction jobId={jobId} />
        <ClosureSection jobId={jobId} />
        {progress.state === 'CLOSED' && <RatingSection jobId={jobId} />}
        {progress.state === 'CLOSED' && <JobPortfolioSection jobId={jobId} />}
        <section aria-labelledby="progress-milestones" className="flex flex-col gap-3">
          <h2 id="progress-milestones" className="text-h2">
            {t('screen.progress.milestones')}
          </h2>
          <ul className="flex flex-col gap-3">
            {progress.milestones.map((milestone) => (
              <MilestoneCard
                key={milestone.milestoneId}
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
                  <li key={member.accountId} className="flex flex-col gap-2 rounded-lg border border-border bg-card p-4">
                    <p className="text-label font-semibold">{member.name}</p>
                    <ItemList items={member.openItems} jobId={jobId} empty={t('screen.progress.noOpenItems')} />
                  </li>
                ))}
              </ul>
            )}
          </section>
        )}
        {audience === 'crew' && (
          <section aria-labelledby="progress-mine" className="flex flex-col gap-3">
            <h2 id="progress-mine" className="text-h2">
              {t('screen.progress.mine')}
            </h2>
            <ItemList items={progress.mine} jobId={jobId} empty={t('screen.progress.noOpenItems')} />
          </section>
        )}
        <section aria-labelledby="progress-pages" className="flex flex-col gap-2">
          <h2 id="progress-pages" className="text-h2">
            {t('screen.progress.pages')}
          </h2>
          {progress.pages.length === 0 ? (
            <p className="text-body-sm text-muted-foreground">{t('screen.progress.noPages')}</p>
          ) : (
            <ul className="flex flex-col gap-1">
              {progress.pages.map((page) => (
                <li key={page.id}>
                  <Link
                    to={`/cong-viec/${jobId}/tai-lieu/${page.id}`}
                    className="text-body-sm text-primary underline-offset-4 hover:underline"
                  >
                    {page.title}
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </section>
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
            progress.crew.map((member) => <p key={member.accountId}>{t('screen.progress.member', { name: member.name })}</p>)}
        </section>
        <section className="flex flex-col gap-1 rounded-lg border border-border bg-card p-4 text-body-sm">
          <h3 className="text-h3">{t('screen.progress.product')}</h3>
          <p className="break-words">{progress.productName}</p>
        </section>
      </aside>
    </div>
  )
}

function MilestoneCard({
  milestone,
  audience,
  nextToFund,
}: {
  milestone: MilestoneProgress
  audience: ProgressAudience
  nextToFund: boolean
}) {
  const textAudience = audience === 'customer' ? 'customer' : 'mentor'
  const percent = milestone.total === 0 ? 0 : Math.round((milestone.done / milestone.total) * 100)
  const text = milestoneStateText(milestone.state, textAudience, {
    done: milestone.done,
    total: milestone.total,
    nextToFund,
    dueOn: milestone.dueOn,
    inReview: audience === 'mentor' ? milestone.inReview : 0,
    roundsLeft: milestone.roundsLeft,
    revisionCriteria: milestone.revisionCriteria,
    autoAccepted: milestone.autoAccepted,
  })
  return (
    <li className="flex flex-col gap-3 rounded-lg border border-border bg-card p-5">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <h3 className="text-h3 break-words">{t('screen.progress.milestone', { n: milestone.number, title: milestone.title })}</h3>
        <span className={cn('rounded-full px-2 py-0.5 text-caption font-semibold', milestoneTone(milestone.state, textAudience, nextToFund))}>
          {text}
        </span>
      </div>
      <div
        role="progressbar"
        aria-label={t('screen.progress.bar', { n: milestone.number })}
        aria-valuemin={0}
        aria-valuemax={milestone.total}
        aria-valuenow={milestone.done}
        aria-valuetext={t('screen.progress.barText', { done: milestone.done, total: milestone.total })}
        className="h-2 w-full overflow-hidden rounded-full bg-muted"
      >
        <div className="h-full rounded-full bg-primary" style={{ width: `${percent}%` }} />
      </div>
      <p className="flex flex-wrap gap-x-5 gap-y-1 text-body-sm tabular-nums">
        {milestone.priceVnd != null && (
          <span>
            {formatVnd(milestone.priceVnd)} <TestTag />
          </span>
        )}
        {milestone.dueOn && <span>{t('screen.progress.due', { date: formatCalendarDayMonth(milestone.dueOn) })}</span>}
      </p>
    </li>
  )
}

function ItemList({ items, jobId, empty }: { items: JobProgress['mine']; jobId: string; empty: string }) {
  if (items.length === 0) {
    return <p className="text-body-sm text-muted-foreground">{empty}</p>
  }
  return (
    <ul className="flex flex-col gap-1 text-body-sm">
      {items.map((item) => (
        <li key={item.id} className="flex flex-wrap items-center gap-2">
          <code className="font-mono text-caption">{item.key}</code>
          <Link to={`/cong-viec/${jobId}/bang`} className="break-words text-primary underline-offset-4 hover:underline">
            {item.title}
          </Link>
          <span className="text-caption text-muted-foreground">{t(`screen.board.status.${item.status}` as MessageKey)}</span>
        </li>
      ))}
    </ul>
  )
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
