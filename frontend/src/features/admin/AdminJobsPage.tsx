import { useQuery } from '@tanstack/react-query'
import { Link, useParams, useSearchParams } from 'react-router'
import type { components } from '@/api/schema'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { Button } from '@/components/ui/button'
import { buttonVariants } from '@/components/ui/button-variants'
import { t, type MessageKey } from '@/i18n/t'
import { apiClient, unwrap } from '@/lib/apiClient'
import { formatCalendarDayMonth, formatDayMonth, formatDayMonthTime } from '@/lib/dates'
import { cn } from '@/lib/utils'

type OversightJob = components['schemas']['OversightJobView']
type Flag = 'OVERDUE' | 'FROZEN'

const STATES = ['OPEN', 'SELECTING', 'AGREEMENT', 'STARTING', 'ACTIVE', 'CLOSING', 'CLOSED', 'CANCELLED'] as const
const FLAGS: Flag[] = ['OVERDUE', 'FROZEN']

/**
 * ADM-5 (`/quan-tri/cong-viec/{jobId?}`, FR-68, FR-63, UX-DR69): Jobs by state, overdue, or frozen, each
 * with its Milestones; the chosen Job links to its Audit Log entries. No Job Chat or Crew Chat is here.
 */
export function AdminJobsPage() {
  usePageTitle(t('screen.admin.jobs.title'))
  const { jobId } = useParams()
  const [params, setParams] = useSearchParams()
  const state = STATES.find((candidate) => candidate === params.get('state'))
  const flag = FLAGS.find((candidate) => candidate === params.get('flag'))
  const page = Math.max(1, Number.parseInt(params.get('page') ?? '1', 10) || 1) - 1
  const jobs = useQuery({
    queryKey: ['admin', 'jobs', state, flag, page],
    queryFn: () => unwrap(apiClient.GET('/api/v1/admin/jobs', { params: { query: { state, flag, page } } })),
    retry: false,
  })

  function update(next: { state?: string; flag?: string; page?: number }) {
    const search = new URLSearchParams()
    const merged = { state, flag, page: 0, ...next }
    if (merged.state) search.set('state', merged.state)
    if (merged.flag) search.set('flag', merged.flag)
    search.set('page', `${merged.page + 1}`)
    setParams(search)
  }

  const pages = Math.max(1, Math.ceil((jobs.data?.total ?? 0) / (jobs.data?.size ?? 20)))
  const search = params.toString() ? `?${params.toString()}` : ''
  const chip = (active: boolean) =>
    cn(
      'min-h-control-compact rounded-full border px-3 text-label focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring',
      active ? 'border-primary bg-primary-soft text-foreground' : 'border-border bg-card text-muted-foreground hover:bg-muted',
    )

  return (
    <div className="flex flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <PageHeading>{t('screen.admin.jobs.heading')}</PageHeading>
      <div role="group" aria-label={t('screen.admin.jobs.stateFilter')} className="flex flex-wrap gap-2">
        {STATES.map((option) => (
          <button
            key={option}
            type="button"
            aria-pressed={state === option}
            onClick={() => update({ state: state === option ? undefined : option })}
            className={chip(state === option)}
          >
            {t(`screen.admin.jobs.state.${option}` as MessageKey)}
          </button>
        ))}
      </div>
      <div role="group" aria-label={t('screen.admin.jobs.flagFilter')} className="flex flex-wrap gap-2">
        {FLAGS.map((option) => (
          <button
            key={option}
            type="button"
            aria-pressed={flag === option}
            onClick={() => update({ flag: flag === option ? undefined : option })}
            className={chip(flag === option)}
          >
            {t(`screen.admin.jobs.flag.${option}` as MessageKey)}
          </button>
        ))}
      </div>
      <div className="flex flex-col gap-5 lg:flex-row lg:items-start">
        <aside className="flex flex-col gap-3 lg:w-[340px] lg:shrink-0">
          <RegionState query={jobs} skeleton={<Skeleton className="h-40 w-full" />}>
            {jobs.data &&
              (jobs.data.items.length === 0 ? (
                <p className="rounded-lg border border-border bg-card p-4 text-body-sm text-muted-foreground">
                  {t('screen.admin.jobs.empty')}
                </p>
              ) : (
                <ul
                  aria-label={t('screen.admin.jobs.listLabel')}
                  className="flex flex-col divide-y divide-border overflow-hidden rounded-lg border border-border bg-card"
                >
                  {jobs.data.items.map((job) => (
                    <li key={job.id}>
                      <Link
                        to={`/quan-tri/cong-viec/${job.id}${search}`}
                        aria-current={job.id === jobId ? 'page' : undefined}
                        className={cn('flex flex-col gap-0.5 p-3 text-body-sm hover:bg-muted', job.id === jobId && 'bg-primary-soft')}
                      >
                        <span className="text-label font-semibold break-words">{job.title}</span>
                        <span className="text-caption text-muted-foreground">
                          {[
                            t(`screen.admin.jobs.state.${job.state}` as MessageKey),
                            job.overdue ? t('screen.admin.jobs.flag.OVERDUE') : '',
                            job.frozen ? t('screen.admin.jobs.flag.FROZEN') : '',
                          ]
                            .filter((part) => part !== '')
                            .join(' · ')}
                        </span>
                      </Link>
                    </li>
                  ))}
                </ul>
              ))}
          </RegionState>
          {pages > 1 && (
            <nav aria-label={t('screen.admin.jobs.pagination')} className="flex items-center justify-between gap-2">
              <Button variant="secondary" size="compact" disabled={page === 0} onClick={() => update({ page: page - 1 })}>
                {t('screen.admin.jobs.previous')}
              </Button>
              <span className="text-caption">{t('screen.admin.jobs.page', { page: page + 1, pages })}</span>
              <Button variant="secondary" size="compact" disabled={page + 1 >= pages} onClick={() => update({ page: page + 1 })}>
                {t('screen.admin.jobs.next')}
              </Button>
            </nav>
          )}
        </aside>
        <div className="flex min-w-0 grow flex-col gap-4">
          {jobId ? (
            <JobPanel key={jobId} jobId={jobId} />
          ) : (
            <p className="rounded-lg border border-dashed border-border bg-card p-6 text-body-sm text-muted-foreground">
              {t('screen.admin.jobs.pick')}
            </p>
          )}
        </div>
      </div>
    </div>
  )
}

function JobPanel({ jobId }: { jobId: string }) {
  const job = useQuery({
    queryKey: ['admin', 'jobs', 'detail', jobId],
    queryFn: () => unwrap(apiClient.GET('/api/v1/admin/jobs/{jobId}', { params: { path: { jobId } } })),
    retry: false,
  })
  return (
    <RegionState query={job} skeleton={<Skeleton className="h-64 w-full" />}>
      {job.data && <JobDetail job={job.data} />}
    </RegionState>
  )
}

function JobDetail({ job }: { job: OversightJob }) {
  return (
    <section aria-labelledby="job-heading" className="flex flex-col gap-3 rounded-lg border border-border bg-card p-5 text-body-sm">
      <h2 id="job-heading" className="text-h2 break-words">
        {job.title}
      </h2>
      <p className="text-muted-foreground">
        {[
          t(`screen.admin.jobs.state.${job.state}` as MessageKey),
          job.customerName ? t('screen.admin.jobs.customer', { name: job.customerName }) : '',
          job.mentorName ? t('screen.admin.jobs.mentor', { name: job.mentorName }) : '',
          t('screen.admin.jobs.updated', { time: formatDayMonthTime(job.updatedAt) }),
        ]
          .filter((part) => part !== '')
          .join(' · ')}
      </p>
      {job.frozen && <p className="text-label text-destructive">{t('screen.admin.jobs.frozenLine')}</p>}
      <h3 className="text-h3">{t('screen.admin.jobs.milestones')}</h3>
      {job.milestones.length === 0 ? (
        <p className="text-muted-foreground">{t('screen.admin.jobs.noMilestones')}</p>
      ) : (
        <ul className="flex flex-col gap-1">
          {job.milestones.map((milestone) => (
            <li key={milestone.id} className="flex flex-wrap gap-x-3">
              <span className="text-label">{t('screen.admin.jobs.milestone', { n: milestone.number, title: milestone.title })}</span>
              <span>{t(`screen.admin.jobs.milestoneState.${milestone.state}` as MessageKey)}</span>
              {milestone.dueOn && <span>{t('screen.admin.jobs.due', { date: formatCalendarDayMonth(milestone.dueOn) })}</span>}
              {milestone.overdueSince && (
                <span className="text-destructive">{t('screen.admin.jobs.overdueSince', { date: formatDayMonth(milestone.overdueSince) })}</span>
              )}
            </li>
          ))}
        </ul>
      )}
      <div>
        <Link to={`/quan-tri/nhat-ky?jobId=${job.id}`} className={buttonVariants({ variant: 'secondary', size: 'compact' })}>
          {t('screen.admin.jobs.auditLink')}
        </Link>
      </div>
    </section>
  )
}
