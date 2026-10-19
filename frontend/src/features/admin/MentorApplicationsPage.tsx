import { ExternalLink, FileText, Eye, Users } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { Button } from '@/components/ui/button'
import { buttonVariants } from '@/components/ui/button-variants'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t, type MessageKey } from '@/i18n/t'
import { isNotFound } from '@/lib/problemMessage'
import { formatCalendarDate, formatDate, formatDayMonth } from '@/lib/dates'
import { formatSize } from '@/lib/fileSize'
import { cn } from '@/lib/utils'
import {
  useMentorApplication,
  useMentorApplicationCounts,
  useMentorApplications,
  type MentorApplicationDetail,
  type MentorApplicationMatch,
  type MentorApplicationQuery,
  type MentorApplicationRow,
  type MentorApplicationStatus,
} from './adminApi'
import { MentorDecisionDialog } from './MentorDecisionDialog'

const STATUSES: MentorApplicationStatus[] = ['PENDING', 'APPROVED', 'REJECTED']

const DEFAULT_SIZE = 20

const BADGE: Record<MentorApplicationStatus, string> = {
  PENDING: 'bg-primary-soft text-primary',
  APPROVED: 'bg-success-soft text-success',
  REJECTED: 'bg-destructive-soft text-destructive',
  REVOKED: 'bg-muted text-muted-foreground',
}

/** The URL's query (`status`, `page` from 1, `size`), with anything unknown as its default. */
function readQuery(params: URLSearchParams): MentorApplicationQuery {
  const status = STATUSES.find((candidate) => candidate === params.get('status')) ?? 'PENDING'
  const page = Math.max(1, Number.parseInt(params.get('page') ?? '1', 10) || 1)
  const size = Math.min(100, Math.max(1, Number.parseInt(params.get('size') ?? `${DEFAULT_SIZE}`, 10) || DEFAULT_SIZE))
  return { status, page: page - 1, size }
}

function nameOf(application: { displayName?: string; githubLogin?: string }): string {
  return application.displayName ?? (application.githubLogin ? `@${application.githubLogin}` : t('screen.admin.mentors.unnamed'))
}

/** The domain of a link the applicant gave, so the Administrator sees where it leads before opening it. */
function domainOf(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, '')
  } catch {
    return url
  }
}

/**
 * ADM-13 (`/quan-tri/nguoi-huong-dan`, FR-74, AD-19): the submitted Mentor applications by status,
 * pending ones oldest first, beside the chosen application (`/quan-tri/nguoi-huong-dan/{accountId}`)
 * with the accounts that may be the same person, never the shared email or link. The checklist is
 * the Administrator's own and never saved; "Duyệt" and "Từ chối" ask for the authenticator code.
 */
export function MentorApplicationsPage() {
  usePageTitle(t('screen.admin.mentors.title'))
  const { accountId } = useParams()
  const [params, setParams] = useSearchParams()
  const query = readQuery(params)
  const applications = useMentorApplications(query)
  const counts = useMentorApplicationCounts()

  function update(next: Partial<{ status: MentorApplicationStatus; page: number }>) {
    const merged = { status: query.status, page: query.page, ...next }
    const search = new URLSearchParams()
    search.set('status', merged.status)
    search.set('page', `${merged.page + 1}`)
    search.set('size', `${query.size}`)
    setParams(search)
  }

  const rows = applications.data?.items ?? []
  const pages = Math.max(1, Math.ceil((applications.data?.total ?? 0) / query.size))
  const search = params.toString() ? `?${params.toString()}` : ''

  return (
    <div className="flex flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <PageHeading>{t('screen.admin.mentors.heading')}</PageHeading>
      <div role="group" aria-label={t('screen.admin.mentors.filter')} className="flex flex-wrap gap-2">
        {STATUSES.map((status) => {
          const label = t(`screen.admin.mentors.${status}` as MessageKey)
          const count = counts[status]
          return (
            <button
              key={status}
              type="button"
              aria-pressed={query.status === status}
              onClick={() => update({ status, page: 0 })}
              className={cn(
                'min-h-control-compact rounded-full border px-3 text-label focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring',
                query.status === status
                  ? 'border-primary bg-primary-soft text-foreground'
                  : 'border-border bg-card text-muted-foreground hover:bg-muted',
              )}
            >
              {count === undefined ? label : t('screen.admin.mentors.chip', { label, n: count })}
            </button>
          )
        })}
      </div>
      <div className="flex flex-col gap-5 lg:flex-row lg:items-start">
        <aside className="flex flex-col gap-3 lg:w-[280px] lg:shrink-0">
          <RegionState query={applications} skeleton={<Skeleton className="h-40 w-full" />}>
            {rows.length === 0 ? (
              <p className="rounded-lg border border-border bg-card p-4 text-body-sm text-muted-foreground">
                {t(`screen.admin.mentors.empty${query.status}` as MessageKey)}
              </p>
            ) : (
              <ul
                aria-label={t('screen.admin.mentors.listLabel')}
                className="flex flex-col divide-y divide-border overflow-hidden rounded-lg border border-border bg-card"
              >
                {rows.map((row) => (
                  <ApplicationItem
                    key={row.accountId}
                    row={row}
                    to={`/quan-tri/nguoi-huong-dan/${row.accountId}${search}`}
                    current={row.accountId === accountId}
                  />
                ))}
              </ul>
            )}
          </RegionState>
          <p className="text-caption text-muted-foreground">
            {query.status === 'PENDING' ? t('screen.admin.mentors.notePENDING') : t('screen.admin.mentors.noteDecided')}
          </p>
          {pages > 1 && (
            <nav aria-label={t('screen.admin.mentors.pagination')} className="flex items-center justify-between gap-2">
              <Button variant="secondary" size="compact" disabled={query.page === 0} onClick={() => update({ page: query.page - 1 })}>
                {t('screen.admin.mentors.previous')}
              </Button>
              <span className="text-caption">{t('screen.admin.mentors.page', { page: query.page + 1, pages })}</span>
              <Button
                variant="secondary"
                size="compact"
                disabled={query.page + 1 >= pages}
                onClick={() => update({ page: query.page + 1 })}
              >
                {t('screen.admin.mentors.next')}
              </Button>
            </nav>
          )}
        </aside>
        <div className="flex min-w-0 grow flex-col gap-4">
          {accountId ? (
            <ApplicationPanel key={accountId} applicantId={accountId} />
          ) : (
            <p className="rounded-lg border border-dashed border-border bg-card p-6 text-body-sm text-muted-foreground">
              {t('screen.admin.mentors.pick')}
            </p>
          )}
        </div>
      </div>
    </div>
  )
}

function ApplicationItem({ row, to, current }: { row: MentorApplicationRow; to: string; current: boolean }) {
  const years = row.yearsOfExperience ?? 0
  const meta =
    row.status === 'PENDING' || !row.decidedAt
      ? t('screen.admin.mentors.rowMeta', { date: row.submittedAt ? formatDayMonth(row.submittedAt) : '—', years })
      : t('screen.admin.mentors.rowDecided', { date: formatDayMonth(row.decidedAt), years })
  return (
    <li>
      <Link
        to={to}
        aria-current={current ? 'true' : undefined}
        className={cn(
          'flex flex-col gap-0.5 px-4 py-3 focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-ring',
          current ? 'bg-primary-soft' : 'hover:bg-muted',
        )}
      >
        <span className="truncate text-body-sm font-semibold">{nameOf(row)}</span>
        <span className="text-caption text-muted-foreground">{meta}</span>
        {row.matches > 0 && (
          <span className="mt-1 w-fit rounded-full bg-warning-soft px-2 py-0.5 text-caption font-semibold text-warning">
            {t('screen.admin.mentors.matchBadge', { n: row.matches })}
          </span>
        )}
      </Link>
    </li>
  )
}

function ApplicationPanel({ applicantId }: { applicantId: string }) {
  const application = useMentorApplication(applicantId)
  if (application.isError && isNotFound(application.error)) {
    return <NotFoundScreen />
  }
  return (
    <RegionState query={application} skeleton={<Skeleton className="h-96 w-full" />}>
      {application.data && <ApplicationDetail application={application.data} />}
    </RegionState>
  )
}

function ApplicationDetail({ application }: { application: MentorApplicationDetail }) {
  const [deciding, setDeciding] = useState<'approve' | 'reject'>()
  const name = nameOf(application)
  const meta = application.submittedAt
    ? application.githubLogin
      ? t('screen.admin.mentors.meta', { login: application.githubLogin, date: formatDate(application.submittedAt) })
      : t('screen.admin.mentors.metaNoLogin', { date: formatDate(application.submittedAt) })
    : undefined
  const studentByEmail = application.matches.some(
    (match) => match.role === 'STUDENT' && match.kind === 'EMAIL' && !!match.verifiedUntil,
  )
  const checks: { key: MessageKey; warning?: boolean }[] = [
    { key: 'screen.admin.mentors.check1' },
    { key: 'screen.admin.mentors.check2' },
    { key: 'screen.admin.mentors.check3', warning: studentByEmail },
  ]

  return (
    <>
      <section className="flex flex-col gap-1">
        <div className="flex flex-wrap items-center gap-2">
          <h2 className="text-h2 break-words">{name}</h2>
          <span className={cn('rounded-full px-2 py-0.5 text-caption font-semibold', BADGE[application.status])}>
            {t(`screen.admin.mentors.status${application.status}` as MessageKey)}
          </span>
        </div>
        {meta && <p className="text-caption text-muted-foreground">{meta}</p>}
        {application.decidedAt && application.status !== 'PENDING' && (
          <p className="text-body-sm break-words">
            {application.status === 'REJECTED'
              ? t('screen.admin.mentors.decidedREJECTED', {
                  date: formatDate(application.decidedAt),
                  reason: application.rejectionReason ?? '',
                })
              : t('screen.admin.mentors.decidedAPPROVED', { date: formatDate(application.decidedAt) })}
          </p>
        )}
      </section>
      <Matches matches={application.matches} />
      <section aria-labelledby="mentor-profile-heading" className="flex flex-col gap-3.5 rounded-lg border border-border bg-card p-5">
        <h3 id="mentor-profile-heading" className="text-h3">
          {t('screen.admin.mentors.profileHeading')}
        </h3>
        <div className="flex flex-col gap-0.5">
          <p className="text-caption text-muted-foreground">{t('screen.admin.mentors.experience')}</p>
          <p className="text-body-sm break-words whitespace-pre-line">{application.experience}</p>
        </div>
        <div className="grid gap-3 sm:grid-cols-2">
          <div className="flex flex-col gap-0.5">
            <p className="text-caption text-muted-foreground">{t('screen.admin.mentors.years')}</p>
            <p className="text-body-sm font-semibold">
              {application.yearsOfExperience === undefined
                ? '—'
                : t('screen.admin.mentors.yearsValue', { n: application.yearsOfExperience })}
            </p>
          </div>
          {application.linkedinUrl && (
            <OutsideLink label={t('screen.admin.mentors.linkedin')} url={application.linkedinUrl} />
          )}
          {application.otherLink && <OutsideLink label={t('screen.admin.mentors.otherLink')} url={application.otherLink} />}
        </div>
        <div className="flex flex-col gap-1.5">
          <p className="text-caption text-muted-foreground">{t('screen.admin.mentors.skills')}</p>
          <ul className="flex flex-wrap gap-1.5">
            {application.skills.map((skill) => (
              <li key={skill.id} className="rounded-full border border-border px-2.5 py-0.5 text-caption">
                {skill.name}
              </li>
            ))}
          </ul>
        </div>
        {application.document ? (
          <div className="flex flex-wrap items-center gap-3 rounded-lg border border-border bg-background px-3.5 py-2.5">
            <FileText aria-hidden="true" className="size-5 shrink-0 text-muted-foreground" />
            <div className="flex min-w-0 grow flex-col gap-0.5">
              <p className="truncate text-body-sm">
                {t('screen.admin.mentors.documentLine', {
                  name: application.document.name,
                  size: formatSize(application.document.size),
                })}
              </p>
              <p className="text-caption text-muted-foreground">{t('screen.admin.mentors.documentCaption')}</p>
            </div>
            <a
              href={application.document.fileUrl}
              target="_blank"
              rel="noopener noreferrer"
              className={cn(buttonVariants({ variant: 'secondary', size: 'compact' }), 'gap-1.5')}
            >
              <Eye aria-hidden="true" className="size-4" />
              {t('screen.admin.mentors.documentOpen')}
              <span className="sr-only">{t('screen.admin.mentors.newTab')}</span>
            </a>
          </div>
        ) : (
          <p className="text-caption text-muted-foreground">{t('screen.admin.mentors.documentNone')}</p>
        )}
      </section>
      {application.status === 'PENDING' && (
        <>
          <section aria-labelledby="mentor-checks-heading" className="flex flex-col gap-2.5 rounded-lg border border-border bg-card p-5">
            <h3 id="mentor-checks-heading" className="text-h3">
              {t('screen.admin.mentors.checksHeading')}
            </h3>
            {checks.map((check) => (
              <label key={check.key} className="flex cursor-pointer items-start gap-3">
                <input type="checkbox" className="mt-0.5 size-5 shrink-0 accent-primary" />
                <span className="text-body-sm">
                  {t(check.key)}
                  {check.warning && <span className="text-caption text-warning"> {t('screen.admin.mentors.check3Warning')}</span>}
                </span>
              </label>
            ))}
            <p className="text-caption text-muted-foreground">{t('screen.admin.mentors.checksNote')}</p>
          </section>
          <div className="flex flex-wrap items-center justify-between gap-3">
            <p className="text-caption text-muted-foreground">{t('screen.admin.mentors.stepUpHint')}</p>
            <div className="flex gap-3">
              <Button variant="secondary" className="border-destructive text-destructive" onClick={() => setDeciding('reject')}>
                {t('screen.admin.mentors.reject')}
              </Button>
              <Button onClick={() => setDeciding('approve')}>{t('screen.admin.mentors.approve')}</Button>
            </div>
          </div>
          <MentorDecisionDialog
            decision={deciding}
            applicantId={application.accountId}
            name={name}
            hasDocument={!!application.document}
            onClose={() => setDeciding(undefined)}
          />
        </>
      )}
    </>
  )
}

function OutsideLink({ label, url }: { label: string; url: string }) {
  return (
    <div className="flex min-w-0 flex-col gap-0.5">
      <p className="text-caption text-muted-foreground">{label}</p>
      <p className="text-body-sm break-all">
        <a href={url} target="_blank" rel="noopener noreferrer nofollow" className="text-primary underline-offset-4 hover:underline">
          {url}
          <ExternalLink aria-hidden="true" className="ml-1 inline size-3.5" />
          <span className="sr-only">{t('screen.admin.mentors.newTab')}</span>
        </a>{' '}
        <span className="text-caption text-muted-foreground">{t('screen.admin.mentors.domain', { domain: domainOf(url) })}</span>
      </p>
    </div>
  )
}

/** The accounts that may be the same person: what they are, never the shared value (AD-19). */
function Matches({ matches }: { matches: MentorApplicationMatch[] }) {
  if (matches.length === 0) {
    return (
      <section className="flex gap-3 rounded-md border-l-4 border-success bg-success-soft p-4">
        <Users aria-hidden="true" className="size-5 shrink-0 text-success" />
        <p className="text-body-sm">
          <span className="font-semibold">{t('screen.admin.mentors.matchesNone')}</span> {t('screen.admin.mentors.matchesNoneBody')}
        </p>
      </section>
    )
  }
  return (
    <section aria-labelledby="mentor-matches-heading" className="flex gap-3 rounded-md border-l-4 border-warning bg-warning-soft p-4">
      <Users aria-hidden="true" className="size-5 shrink-0 text-warning" />
      <div className="flex min-w-0 grow flex-col gap-2">
        <p id="mentor-matches-heading" className="text-label font-semibold">
          {t('screen.admin.mentors.matchesHeading', { n: matches.length })}
        </p>
        <div className="overflow-x-auto rounded-md border border-border bg-card">
          <table className="w-full text-left text-body-sm" aria-label={t('screen.admin.mentors.matchesLabel')}>
            <thead className="bg-muted text-caption text-muted-foreground">
              <tr>
                <th scope="col" className="px-3 py-2 font-semibold">
                  {t('screen.admin.mentors.colRole')}
                </th>
                <th scope="col" className="px-3 py-2 font-semibold">
                  {t('screen.admin.mentors.colState')}
                </th>
                <th scope="col" className="px-3 py-2 font-semibold">
                  {t('screen.admin.mentors.colKind')}
                </th>
                <th scope="col" className="px-3 py-2 font-semibold">
                  {t('screen.admin.mentors.colCreated')}
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {matches.map((match) => (
                <tr key={`${match.accountId}-${match.kind}`}>
                  <td className="px-3 py-2">{t(`screen.admin.mentors.role${match.role}` as MessageKey)}</td>
                  <td className="px-3 py-2">
                    <MatchState match={match} />
                  </td>
                  <td className="px-3 py-2">{t(`screen.admin.mentors.kind${match.kind}` as MessageKey)}</td>
                  <td className="px-3 py-2">{formatDate(match.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="text-body-sm">{t('screen.admin.mentors.matchesNote')}</p>
      </div>
    </section>
  )
}

function MatchState({ match }: { match: MentorApplicationMatch }) {
  if (match.erased) {
    return <span>{t('screen.admin.mentors.stateErased')}</span>
  }
  if (match.locked) {
    return <span>{t('screen.admin.mentors.stateLocked')}</span>
  }
  if (match.role === 'STUDENT') {
    if (!match.verifiedUntil) {
      return <span>{t('screen.admin.mentors.stateUnverified')}</span>
    }
    // "09/2027": the month the verification ends.
    const month = formatCalendarDate(match.verifiedUntil).slice(3)
    const today = new Date().toISOString().slice(0, 10)
    return match.verifiedUntil >= today ? (
      <span className="rounded-full bg-success-soft px-2 py-0.5 text-caption font-semibold text-success">
        {t('screen.admin.mentors.stateVerified', { month })}
      </span>
    ) : (
      <span>{t('screen.admin.mentors.stateExpired', { month })}</span>
    )
  }
  if (match.role === 'MENTOR' && match.mentorStatus) {
    return <span>{t(`screen.admin.mentors.status${match.mentorStatus}` as MessageKey)}</span>
  }
  return <span>{t('screen.admin.mentors.stateActive')}</span>
}
