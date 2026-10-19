import { Check, Clock, Search, Send, Users, X } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { Link, Navigate, useParams, useSearchParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { inputClass } from '@/components/formField-props'
import { Pager } from '@/components/Pager'
import { RegionState } from '@/components/RegionState'
import { Sheet } from '@/components/Sheet'
import { Skeleton } from '@/components/Skeleton'
import { SkillPicker } from '@/components/SkillPicker'
import { categoryValue, splitSkillPickerValue } from '@/components/skillPickerValue'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { useSession } from '@/features/identity/useSession'
import { useJob, type Job } from '@/features/job/jobApi'
import { JobStateBadge } from '@/features/job/JobStateBadge'
import { JobTabs } from '@/features/job/JobTabs'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { MentorProfileContent } from '@/features/team/MentorProfileContent'
import { usePublicMentor } from '@/features/team/teamApi'
import { initials } from '@/features/team/teamText'
import { t, type MessageKey } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { isNotFound, problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { useJobApplications } from './applicationApi'
import {
  useInvitations,
  useInvite,
  useMentorSearch,
  useMentorSummaries,
  type Invitations,
  type MentorFilter,
  type MentorResult,
  type MentorSummary,
} from './invitationApi'

/** The keyword and Skill filter in the URL, as on MEN-2 (UX-DR72, UX-DR75). */
const PARAMS = { q: 'q', skills: 'ky-nang', categories: 'nhom-ky-nang', page: 'trang' }

/** The Search bar's limit (EXPERIENCE.md, Search bar). */
const MAX_KEYWORD = 100

function readFilter(params: URLSearchParams): MentorFilter {
  const list = (key: string) => (params.get(key) ?? '').split(',').filter((value) => value !== '')
  const q = (params.get(PARAMS.q) ?? '').trim().slice(0, MAX_KEYWORD)
  const page = Number.parseInt(params.get(PARAMS.page) ?? '1', 10)
  return {
    q: q === '' ? undefined : q,
    skills: list(PARAMS.skills),
    categories: list(PARAMS.categories),
    page: Number.isFinite(page) && page > 1 ? page - 1 : 0,
  }
}

function writeFilter(filter: MentorFilter): URLSearchParams {
  const params = new URLSearchParams()
  if (filter.q) params.set(PARAMS.q, filter.q)
  if (filter.skills.length > 0) params.set(PARAMS.skills, filter.skills.join(','))
  if (filter.categories.length > 0) params.set(PARAMS.categories, filter.categories.join(','))
  if (filter.page > 0) params.set(PARAMS.page, String(filter.page + 1))
  return params
}

/**
 * CUS-8 "Tìm người hướng dẫn" (`/cong-viec/{jobId}/tim-nguoi`, FR-14, UX-DR47–49, UX-DR66): the Job's
 * Customer searches approved Mentors by keyword and Skills, ordered by relevance alone, sees each
 * one's Team size and Rating or "Mới", opens PUB-5 in a Sheet, and invites them within the day's
 * counters. The tab exists only while the Job is Open; afterwards the route goes to "Đơn ứng tuyển".
 */
export function MentorSearchPage() {
  const { jobId } = useParams()
  const job = useJob(jobId)
  const role = useSession().data?.account?.role
  usePageTitle(job.data ? t('screen.matching.find.title', { job: job.data.title }) : t('screen.job.title'))
  if ((role !== undefined && role !== 'CUSTOMER') || (job.isError && isNotFound(job.error))) {
    return <NotFoundScreen />
  }
  if (job.data && job.data.state !== 'OPEN') {
    return <Navigate to={`/cong-viec/${job.data.id}/ung-tuyen`} replace />
  }
  return (
    <div className="mx-auto flex max-w-[1120px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <RegionState query={job} skeleton={<Skeleton className="h-96 w-full" />}>
        {job.data && <FindMentors job={job.data} />}
      </RegionState>
    </div>
  )
}

function FindMentors({ job }: { job: Job }) {
  const [params, setParams] = useSearchParams()
  const filter = readFilter(params)
  const applications = useJobApplications(job.id)
  const invitations = useInvitations(job.id)
  const search = useMentorSearch(job.id, filter)
  const [profileId, setProfileId] = useState<string>()
  const ids = useId()
  const update = (next: Partial<MentorFilter>) => setParams(writeFilter({ ...filter, page: 0, ...next }))
  const items = search.data?.items ?? []
  const summaries = useMentorSummaries(items.map((item) => item.accountId))
  const byId = new Map((summaries.data ?? []).map((summary) => [summary.accountId, summary]))
  const quota = invitations.data
  const profile = items.find((item) => item.accountId === profileId)
  return (
    <>
      <div className="flex flex-col gap-3">
        <nav aria-label={t('screen.job.detail.breadcrumb')} className="text-caption text-muted-foreground">
          <Link to="/cong-viec" className="text-primary underline-offset-4 hover:underline">
            {t('screen.job.detail.breadcrumb')}
          </Link>{' '}
          / <span className="break-words">{job.title}</span>
        </nav>
        <div className="flex flex-wrap items-center gap-2">
          <PageHeading>{job.title}</PageHeading>
          <JobStateBadge state={job.state} />
        </div>
        <JobTabs jobId={job.id} current="find" applications={applications.data?.length ?? 0} />
      </div>
      <QuotaLine quota={quota} />
      <SearchBar key={filter.q ?? ''} keyword={filter.q} search={(q) => update({ q })} />
      <div className="flex max-w-[560px] flex-col gap-1">
        <label htmlFor={`${ids}-skills`} className="text-caption">
          {t('screen.matching.find.skills')}
        </label>
        <SkillPicker
          id={`${ids}-skills`}
          value={[...filter.categories.map(categoryValue), ...filter.skills]}
          onChange={(values) => update(splitSkillPickerValue(values))}
          max={20}
          wholeCategories
          describedBy={`${ids}-skills-hint`}
        />
        <span id={`${ids}-skills-hint`} className="text-caption text-muted-foreground">
          {t('screen.matching.find.skillsHint')}
        </span>
      </div>
      {filter.q === undefined ? (
        <div className="rounded-lg border border-border bg-card p-8 text-center text-body-sm text-muted-foreground">
          {t('screen.matching.find.noRecommendation')}
        </div>
      ) : (
        <RegionState query={search} skeleton={<Skeleton className="h-48 w-full" />}>
          {search.data && (
            <section aria-labelledby={`${ids}-results`} className="flex flex-col gap-3">
              <div className="flex flex-col gap-0.5">
                <h2 id={`${ids}-results`} aria-live="polite" className="text-h3">
                  {t('screen.matching.find.results', { q: filter.q, n: search.data.total })}
                </h2>
                <p className="text-caption text-muted-foreground">{t('screen.matching.find.order')}</p>
              </div>
              {items.length === 0 ? (
                <div className="flex flex-col gap-1 rounded-lg border border-border bg-card p-8 text-center">
                  <p className="text-label font-semibold">{t('screen.matching.find.empty', { q: filter.q })}</p>
                  <p className="text-body-sm text-muted-foreground">{t('screen.matching.find.emptyHint')}</p>
                </div>
              ) : (
                <ul className="flex flex-col gap-3">
                  {items.map((mentor) => (
                    <MentorCard
                      key={mentor.accountId}
                      job={job}
                      mentor={mentor}
                      summary={byId.get(mentor.accountId)}
                      limited={!!quota?.limit}
                      onProfile={() => setProfileId(mentor.accountId)}
                    />
                  ))}
                </ul>
              )}
              <Pager
                page={search.data.page}
                pages={Math.ceil(search.data.total / search.data.size)}
                go={(page) => update({ page })}
              />
            </section>
          )}
        </RegionState>
      )}
      <Sheet
        open={profile !== undefined}
        onClose={() => setProfileId(undefined)}
        title={profile?.displayName ?? ''}
        description={profile && t('screen.matching.find.sheetRole', { years: profile.yearsOfExperience })}
        footer={
          profile && (
            <>
              {quota && !quota.limit && (
                <span className="mr-auto text-caption text-muted-foreground">
                  {t('screen.matching.find.quotaShort', { n: quota.left })}
                </span>
              )}
              <InviteButton job={job} mentor={profile} limited={!!quota?.limit} />
            </>
          )
        }
      >
        {profile && <SheetProfile username={profile.githubLogin ?? undefined} />}
      </Sheet>
    </>
  )
}

/** "Còn {n} lời mời hôm nay…", or the used-up limit's warning (UX-DR49). */
function QuotaLine({ quota }: { quota: Invitations | undefined }) {
  if (!quota) {
    return null
  }
  if (quota.limit) {
    return (
      <div role="note" className="flex gap-3 rounded-md border-l-4 border-warning bg-warning-soft p-4 text-body-sm">
        <Clock aria-hidden="true" className="size-5 shrink-0 text-warning" />
        <p>{t(quota.limit === 'INVITE_LIMIT_DAILY' ? 'screen.matching.find.limitDaily' : 'screen.matching.find.limitJobPost')}</p>
      </div>
    )
  }
  return <p className="text-caption text-muted-foreground">{t('screen.matching.find.quota', { n: quota.left })}</p>
}

/** The Search bar (EXPERIENCE.md): Enter or "Tìm" runs it, typing alone never does (Telex, VNI). */
function SearchBar({ keyword, search }: { keyword: string | undefined; search: (q: string | undefined) => void }) {
  const ids = useId()
  const [input, setInput] = useState(keyword ?? '')
  const submit = (event: FormEvent) => {
    event.preventDefault()
    const q = input.trim()
    if (q !== '') {
      search(q)
    }
  }
  return (
    <form role="search" onSubmit={submit} className="flex flex-col gap-1.5">
      <label htmlFor={`${ids}-q`} className="text-label font-semibold">
        {t('screen.matching.find.searchLabel')}
      </label>
      <div className="flex gap-2">
        <div className="relative min-w-0 grow">
          <Search aria-hidden="true" className="pointer-events-none absolute top-1/2 left-3 size-5 -translate-y-1/2 text-muted-foreground" />
          <input
            id={`${ids}-q`}
            type="search"
            maxLength={MAX_KEYWORD}
            value={input}
            onChange={(event) => setInput(event.target.value)}
            placeholder={t('screen.matching.find.searchPlaceholder')}
            aria-describedby={`${ids}-help`}
            className={cn(inputClass, 'pl-10')}
          />
        </div>
        <Button type="submit" disabled={input.trim() === ''}>
          {t('screen.jobPost.searchButton')}
        </Button>
        {keyword && (
          <Button type="button" variant="secondary" onClick={() => search(undefined)}>
            <X aria-hidden="true" className="size-4" />
            {t('screen.jobPost.clearKeyword')}
          </Button>
        )}
      </div>
      <p id={`${ids}-help`} className="text-caption text-muted-foreground">
        {t('screen.matching.find.searchHelper')}
      </p>
    </form>
  )
}

/** A Profile result card (UX-DR48): no contact details, and the Rating never changes the order. */
function MentorCard({
  job,
  mentor,
  summary,
  limited,
  onProfile,
}: {
  job: Job
  mentor: MentorResult
  summary: MentorSummary | undefined
  limited: boolean
  onProfile: () => void
}) {
  const rating =
    summary && summary.count > 0 && summary.average != null
      ? t('screen.matching.find.rating', {
          average: summary.average.toLocaleString('vi-VN', { maximumFractionDigits: 1 }),
          count: summary.count,
        })
      : summary?.newcomer
        ? t('screen.matching.find.newcomer')
        : undefined
  return (
    <li className="flex flex-wrap items-start gap-3 rounded-lg border border-border bg-card p-4">
      <span
        aria-hidden="true"
        className="flex size-10 shrink-0 items-center justify-center rounded-full bg-primary-soft text-label font-semibold text-primary"
      >
        {initials(mentor.displayName)}
      </span>
      <div className="flex min-w-[220px] grow flex-col gap-1.5">
        <p className="text-label font-semibold break-words">{mentor.displayName}</p>
        <p className="flex flex-wrap items-center gap-1.5 text-caption text-muted-foreground">
          {t('screen.matching.find.years', { years: mentor.yearsOfExperience })} ·
          <Users aria-hidden="true" className="size-4" />
          {t('screen.matching.find.students', { n: mentor.teamSize })}
        </p>
        {(mentor.skills.length > 0 || rating) && (
          <ul className="flex flex-wrap gap-1.5">
            {mentor.skills.map((skill) => (
              <li key={skill} className="rounded-full bg-muted px-2 py-0.5 text-caption">
                {skill}
              </li>
            ))}
            {rating && <li className="rounded-full bg-primary-soft px-2 py-0.5 text-caption font-semibold text-primary">{rating}</li>}
          </ul>
        )}
        {mentor.githubLogin && (
          <button type="button" onClick={onProfile} className="w-fit text-body-sm text-primary underline-offset-4 hover:underline">
            {t('screen.matching.find.profile')}
          </button>
        )}
      </div>
      <InviteButton job={job} mentor={mentor} limited={limited} />
    </li>
  )
}

/**
 * "Mời ứng tuyển" (FR-14, UX-DR49): no Confirmation dialog and no optimistic update; once the server
 * confirms, the button reads "Đã mời". Every one is disabled at a used-up limit.
 */
function InviteButton({ job, mentor, limited }: { job: Job; mentor: MentorResult; limited: boolean }) {
  const invite = useInvite(job.id)
  const toasts = useToasts()
  if (mentor.relation !== 'NONE') {
    return (
      <Button type="button" variant="secondary" size="compact" disabled>
        <Check aria-hidden="true" className="size-4" />
        {t(mentor.relation === 'INVITED' ? 'screen.matching.find.invited' : 'screen.matching.find.applied')}
      </Button>
    )
  }
  const send = () =>
    invite.mutate(mentor.accountId, {
      onSuccess: () => toasts.success(t('screen.matching.find.sent', { name: mentor.displayName })),
      onError: (error) => toasts.error(inviteError(error)),
    })
  return (
    <Button type="button" variant="secondary" size="compact" disabled={limited || invite.isPending} onClick={send}>
      <Send aria-hidden="true" className="size-4" />
      {t('screen.matching.find.invite')}
    </Button>
  )
}

/** A refusal in plain words; a used-up counter says which one (AD-34). */
function inviteError(error: unknown): string {
  if (error instanceof ApiError && error.status === 429 && error.problem.reason) {
    return t(`reason.matching.${error.problem.reason}` as MessageKey)
  }
  return problemMessage(error, { module: 'matching', action: t('screen.matching.find.inviteAction') }).text
}

/** PUB-5's content in the Sheet, with the no-contact note (UX-DR48). */
function SheetProfile({ username }: { username: string | undefined }) {
  const mentor = usePublicMentor(username)
  return (
    <>
      <RegionState query={mentor} skeleton={<Skeleton className="h-64 w-full" />}>
        {mentor.data && <MentorProfileContent mentor={mentor.data} visitor={false} />}
      </RegionState>
      <p className="text-caption text-muted-foreground">{t('screen.matching.find.noContact')}</p>
    </>
  )
}
