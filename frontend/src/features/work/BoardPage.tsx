import { Copy, Link2 } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link, Navigate, useParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { useToasts } from '@/components/toasts'
import { useSession } from '@/features/identity/useSession'
import { JobHeader } from '@/features/job/JobHeader'
import { mentorMeta } from '@/features/job/jobMeta'
import { MentorJobTabs } from '@/features/matching/MentorJobTabs'
import { useMentorJob } from '@/features/matching/mentorJobApi'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t, type MessageKey } from '@/i18n/t'
import { isNotFound } from '@/lib/problemMessage'
import { hasBoard, STATUSES, useBoard, type Board, type WorkItemCard } from './workApi'

/**
 * JOB-2 "Bảng công việc" (`/cong-viec/{jobId}/bang`, FR-22, FR-23, AD-33, AD-39, UX-DR36): the Board
 * of an Active Job for its Mentor and its active Crew, in four columns. Each card shows its key with
 * a copy button for commit messages, its type, its title, and for a seeded Story a link to the
 * criterion it came from; a seeded Story's criterion, type, and Milestone never change. Creating,
 * editing, and moving Work Items come with Stories 5.2 and 5.3. Anyone else sees the not-found screen.
 */
export function BoardPage() {
  const { jobId } = useParams()
  const account = useSession().data?.account
  if (!jobId || (account !== undefined && account.role !== 'MENTOR' && account.role !== 'STUDENT')) {
    return <NotFoundScreen />
  }
  return account?.role === 'MENTOR' ? <MentorBoard jobId={jobId} /> : <StudentBoard jobId={jobId} />
}

function MentorBoard({ jobId }: { jobId: string }) {
  const job = useMentorJob(jobId)
  const board = useBoard(job.data && hasBoard(job.data.state) ? jobId : undefined)
  usePageTitle(job.data ? t('screen.board.title', { job: job.data.title }) : t('screen.job.title'))
  if ((job.isError && isNotFound(job.error)) || (board.isError && isNotFound(board.error))) {
    return <NotFoundScreen />
  }
  if (job.data && !hasBoard(job.data.state)) {
    return <Navigate to={`/cong-viec/${job.data.id}`} replace />
  }
  return (
    <Frame>
      <RegionState query={job} skeleton={<Skeleton className="h-24 w-full" />}>
        {job.data && (
          <JobHeader title={job.data.title} state={job.data.state} meta={mentorMeta(job.data)} audience="mentor">
            <MentorJobTabs jobId={job.data.id} state={job.data.state} current="board" payment={job.data.agreementConfirmed} />
          </JobHeader>
        )}
      </RegionState>
      {job.data && (
        <RegionState query={board} skeleton={<Skeleton className="h-96 w-full" />}>
          {board.data && <Columns board={board.data} jobId={jobId} />}
        </RegionState>
      )}
    </Frame>
  )
}

function StudentBoard({ jobId }: { jobId: string }) {
  const board = useBoard(jobId)
  usePageTitle(board.data ? t('screen.board.title', { job: board.data.title }) : t('screen.board.tab'))
  if (board.isError && isNotFound(board.error)) {
    return <NotFoundScreen />
  }
  return (
    <Frame>
      <div className="flex flex-col gap-1">
        <Link to="/cong-viec" className="w-fit text-label text-muted-foreground underline-offset-4 hover:underline">
          {t('screen.crewChat.back')}
        </Link>
        <PageHeading>{board.data?.title ?? t('screen.board.tab')}</PageHeading>
        <p className="text-body-sm text-muted-foreground">{t('screen.board.tab')}</p>
      </div>
      <RegionState query={board} skeleton={<Skeleton className="h-96 w-full" />}>
        {board.data && <Columns board={board.data} jobId={jobId} />}
      </RegionState>
    </Frame>
  )
}

function Columns({ board, jobId }: { board: Board; jobId: string }) {
  const milestones = new Map(board.milestones.map((milestone) => [milestone.milestoneId, milestone]))
  return (
    <div className="flex flex-col gap-3">
      {board.items.some((item) => item.seeded) && (
        <p className="text-body-sm text-muted-foreground">{t('screen.board.seededLead')}</p>
      )}
      <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
        {STATUSES.map((status) => {
          const items = board.items.filter((item) => item.status === status)
          return (
            <section
              key={status}
              aria-labelledby={`column-${status}`}
              className="flex min-w-0 flex-col gap-2.5 rounded-lg border border-border bg-muted/40 p-3"
            >
              <h2 id={`column-${status}`} className="flex items-center justify-between text-label font-semibold">
                {t(`screen.board.status.${status}` as MessageKey)}
                <span className="rounded-full bg-background px-2 text-caption text-muted-foreground">{items.length}</span>
              </h2>
              {items.length === 0 ? (
                <p className="text-caption text-muted-foreground">{t('screen.board.emptyColumn')}</p>
              ) : (
                <ul className="flex flex-col gap-2">
                  {items.map((item) => (
                    <Card
                      key={item.id}
                      item={item}
                      jobId={jobId}
                      mentor={board.mentor}
                      milestoneNumber={item.milestoneId ? milestones.get(item.milestoneId)?.number : undefined}
                    />
                  ))}
                </ul>
              )}
            </section>
          )
        })}
      </div>
    </div>
  )
}

function Card({
  item,
  jobId,
  mentor,
  milestoneNumber,
}: {
  item: WorkItemCard
  jobId: string
  mentor: boolean
  milestoneNumber: number | undefined
}) {
  const toasts = useToasts()
  const copy = () => {
    void navigator.clipboard
      ?.writeText(item.key)
      .then(() => toasts.success(t('screen.board.copied', { key: item.key })))
      .catch(() => toasts.error(t('screen.board.copyFailed')))
  }
  const criterion = item.criterion
  const criterionLabel = criterion ? t('screen.board.criterion', { m: criterion.milestoneNumber, n: criterion.number }) : null
  return (
    <li className="flex flex-col gap-2 rounded-md border border-border bg-card p-3 text-body-sm shadow-xs">
      <div className="flex items-center justify-between gap-2">
        <span className="flex items-center gap-1">
          <code className="font-mono text-caption">{item.key}</code>
          <button
            type="button"
            onClick={copy}
            aria-label={t('screen.board.copy', { key: item.key })}
            className="rounded p-0.5 text-muted-foreground hover:bg-muted hover:text-foreground"
          >
            <Copy aria-hidden="true" className="size-3.5" />
          </button>
        </span>
        <span className="rounded-full bg-muted px-2 py-0.5 text-caption font-semibold">
          {t(`screen.board.type.${item.type}` as MessageKey)}
        </span>
      </div>
      <p className="font-semibold break-words">{item.title}</p>
      <div className="flex flex-wrap items-center justify-between gap-2 text-caption text-muted-foreground">
        {criterion && criterionLabel ? (
          mentor ? (
            <Link
              to={`/cong-viec/${jobId}/thoa-thuan`}
              className="flex items-center gap-1 text-primary underline-offset-4 hover:underline"
              aria-label={t('screen.board.criterionLink', { label: criterionLabel })}
            >
              <Link2 aria-hidden="true" className="size-3.5" />
              {criterionLabel}
            </Link>
          ) : (
            <span className="flex items-center gap-1">
              <Link2 aria-hidden="true" className="size-3.5" />
              {criterionLabel}
            </span>
          )
        ) : (
          milestoneNumber !== undefined && <span>{t('screen.board.milestone', { n: milestoneNumber })}</span>
        )}
        <span>{item.assigneeId ? null : t('screen.board.unassigned')}</span>
      </div>
    </li>
  )
}

function Frame({ children }: { children: ReactNode }) {
  return (
    <div className="mx-auto flex max-w-[1280px] flex-col gap-5 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      {children}
    </div>
  )
}
