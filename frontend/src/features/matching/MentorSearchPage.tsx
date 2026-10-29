import { Check, Clock, Search, Send, X } from 'lucide-react'
import { useEffect, useId, useRef, useState, type FormEvent, type RefObject } from 'react'
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
import { useJob, type Job } from '@/features/job/jobApi'
import { JobRoleLine } from '@/features/job/ByJobRole'
import { JobStateBadge } from '@/features/job/JobStateBadge'
import { JobTabs } from '@/features/job/JobTabs'
import { PersonProfileContent } from '@/features/identity/PersonProfile'
import { experienceText, initials } from '@/features/identity/personText'
import { VerifiedFactChips } from '@/features/identity/VerifiedFacts'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t, type MessageKey } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { isNotFound, problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { useJobApplications } from './applicationApi'
import {
  useInvitations,
  useInvite,
  useLastMentorSearch,
  useMentorSearch,
  useMentorSummaries,
  type Invitations,
  type MentorFilter,
  type MentorResult,
  type MentorSummary,
} from './invitationApi'
import { useMentorRecommendations, type RecommendedMentor } from './recommendationApi'
import { SignalChips } from './SignalChips'

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
 * CUS-8 "Tìm người hướng dẫn" (`/cong-viec/{jobId}/tim-nguoi`, FR-14, AD-36, UX-DR100, Story 21.3): the Job's Customer
 * searches the public fields of GitHub-verified, unlocked users by keyword and Skills, ordered by relevance alone; each
 * Profile result card shows the Verified fact chips, the years "Tự khai", the Skills, and the Rating as Mentor or
 * "Mới", which never order anything; PUB-4 opens in a Sheet; "Mời chào giá" invites within the day's counters. No
 * Team size. Searching too fast keeps the earlier results with the wait. Without a keyword, "Đề xuất cho công việc
 * này" shows AD-17's Recommendations. The tab exists only while the Job is Open; afterwards the route goes to "Chào giá".
 */
export function MentorSearchPage() {
  const { jobId } = useParams()
  const job = useJob(jobId)
  usePageTitle(job.data ? t('screen.matching.find.title', { job: job.data.title }) : t('screen.job.title'))
  if (job.isError && isNotFound(job.error)) {
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
  const last = useLastMentorSearch(job.id)
  // Searching too fast keeps the earlier results on screen (UX-DR27, AD-34).
  const limited = search.error instanceof ApiError && search.error.status === 429 ? search.error : undefined
  // While limited, the kept results keep the keyword and filter that found them, for the heading and the pager.
  const kept = limited ? last.data : undefined
  const results = kept ? kept.found : search.data
  const shownFilter = kept ? kept.filter : filter
  const recommendations = useMentorRecommendations(
    job.id,
    { skills: filter.skills, categories: filter.categories },
    filter.q === undefined,
  )
  const [profileId, setProfileId] = useState<string>()
  const ids = useId()
  const searchInput = useRef<HTMLInputElement>(null)
  const update = (next: Partial<MentorFilter>) => setParams(writeFilter({ ...filter, page: 0, ...next }))
  const items = results?.items ?? []
  const recommended = recommendations.data ?? []
  const shown: MentorResult[] = filter.q === undefined ? recommended : items
  const summaries = useMentorSummaries(shown.map((item) => item.accountId))
  const byId = new Map((summaries.data ?? []).map((summary) => [summary.accountId, summary]))
  const quota = invitations.data
  const profile = shown.find((item) => item.accountId === profileId)
  const noRecommendation = filter.q === undefined && recommendations.data?.length === 0
  // With nothing to suggest, the Search bar is where to start (UX-DR27).
  useEffect(() => {
    if (noRecommendation) {
      searchInput.current?.focus()
    }
  }, [noRecommendation])
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
        <JobRoleLine jobId={job.id} state={job.state} />
        <JobTabs jobId={job.id} current="find" applications={applications.data?.length ?? 0} />
      </div>
      <QuotaLine quota={quota} />
      <SearchBar
        key={filter.q ?? ''}
        keyword={filter.q}
        retryAfter={limited ? (limited.retryAfter ?? 60) : undefined}
        search={(q) => update({ q })}
        inputRef={searchInput}
      />
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
        <RegionState query={recommendations} skeleton={<Skeleton className="h-48 w-full" />}>
          {noRecommendation ? (
            <div className="rounded-lg border border-border bg-card p-8 text-center text-body-sm text-muted-foreground">
              {t('screen.matching.find.noRecommendation')}
            </div>
          ) : (
            <section aria-labelledby={`${ids}-recommended`} className="flex flex-col gap-3">
              <h2 id={`${ids}-recommended`} className="text-h2">
                {t('screen.matching.find.recommendations')}
              </h2>
              <ul className="grid gap-3 md:grid-cols-2 lg:grid-cols-3">
                {recommended.map((mentor) => (
                  <RecommendationCard
                    key={mentor.accountId}
                    job={job}
                    mentor={mentor}
                    rating={ratingText(byId.get(mentor.accountId), false)}
                    limited={!!quota?.limit}
                    onProfile={() => setProfileId(mentor.accountId)}
                  />
                ))}
              </ul>
            </section>
          )}
        </RegionState>
      ) : (
        <RegionState query={kept ? last : search} skeleton={<Skeleton className="h-48 w-full" />}>
          {results && (
            <section aria-labelledby={`${ids}-results`} className="flex flex-col gap-3">
              <div className="flex flex-col gap-0.5">
                <h2 id={`${ids}-results`} aria-live="polite" className="text-h3">
                  {t('screen.matching.find.results', { q: shownFilter.q ?? '', n: results.total })}
                </h2>
                <p className="text-caption text-muted-foreground">{t('screen.matching.find.order')}</p>
              </div>
              {items.length === 0 ? (
                <div className="flex flex-col gap-1 rounded-lg border border-border bg-card p-8 text-center">
                  <p className="text-label font-semibold">{t('screen.matching.find.empty', { q: shownFilter.q ?? '' })}</p>
                  <p className="text-body-sm text-muted-foreground">{t('screen.matching.find.emptyHint')}</p>
                </div>
              ) : (
                <ul className="flex flex-col gap-3">
                  {items.map((mentor) => (
                    <MentorCard
                      key={mentor.accountId}
                      job={job}
                      mentor={mentor}
                      rating={ratingText(byId.get(mentor.accountId), true)}
                      limited={!!quota?.limit}
                      onProfile={() => setProfileId(mentor.accountId)}
                    />
                  ))}
                </ul>
              )}
              <Pager
                page={results.page}
                pages={Math.ceil(results.total / results.size)}
                go={(page) => setParams(writeFilter({ ...shownFilter, page }))}
              />
            </section>
          )}
        </RegionState>
      )}
      <Sheet
        open={profile !== undefined}
        onClose={() => setProfileId(undefined)}
        title={profile?.displayName ?? ''}
        description={
          profile && t('screen.matching.find.sheetRole', { experience: experienceText(profile.yearsOfExperience) })
        }
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
        {profile?.githubLogin && <SheetProfile username={profile.githubLogin} />}
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

/**
 * The Search bar (EXPERIENCE.md): Enter or "Tìm" runs it, typing alone never does (Telex, VNI). Searching too fast
 * replaces the helper with the wait from `Retry-After`.
 */
function SearchBar({
  keyword,
  retryAfter,
  search,
  inputRef,
}: {
  keyword: string | undefined
  retryAfter: number | undefined
  search: (q: string | undefined) => void
  inputRef: RefObject<HTMLInputElement | null>
}) {
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
            ref={inputRef}
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
      <p id={`${ids}-help`} className={cn('text-caption', retryAfter ? 'text-warning' : 'text-muted-foreground')}>
        {retryAfter ? t('screen.matching.find.tooFast', { seconds: retryAfter }) : t('screen.matching.find.searchHelper')}
      </p>
    </form>
  )
}

/** The initials, the name, the Verified fact chips, and "{years} năm kinh nghiệm" marked "Tự khai" (UX-DR100). */
function MentorHeading({ mentor }: { mentor: MentorResult }) {
  return (
    <>
      <span
        aria-hidden="true"
        className="flex size-10 shrink-0 items-center justify-center rounded-full bg-primary-soft text-label font-semibold text-primary"
      >
        {initials(mentor.displayName)}
      </span>
      <div className="flex min-w-0 flex-col gap-1">
        <p className="text-label font-semibold break-words">{mentor.displayName}</p>
        <VerifiedFactChips facts={mentor.verified} />
        <p className="text-caption text-muted-foreground">
          {experienceText(mentor.yearsOfExperience)} ({t('screen.person.selfDeclared')})
        </p>
      </div>
    </>
  )
}

function ProfileLink({ mentor, onProfile }: { mentor: MentorResult; onProfile: () => void }) {
  if (!mentor.githubLogin) {
    return <span />
  }
  return (
    <button type="button" onClick={onProfile} className="w-fit text-body-sm text-primary underline-offset-4 hover:underline">
      {t('screen.matching.find.profile')}
    </button>
  )
}

/** A Profile result card (UX-DR48): no contact details, and the Rating never changes the order. */
function MentorCard({
  job,
  mentor,
  rating,
  limited,
  onProfile,
}: {
  job: Job
  mentor: MentorResult
  rating: string | undefined
  limited: boolean
  onProfile: () => void
}) {
  return (
    <li className="flex flex-wrap items-start gap-3 rounded-lg border border-border bg-card p-4">
      <div className="flex min-w-[220px] grow flex-col gap-1.5">
        <div className="flex items-start gap-3">
          <MentorHeading mentor={mentor} />
        </div>
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
        <ProfileLink mentor={mentor} onProfile={onProfile} />
      </div>
      <InviteButton job={job} mentor={mentor} limited={limited} />
    </li>
  )
}

/** A Recommendation card (AD-17, UX-DR48): its signals, the Rating chip, "Xem hồ sơ", and Invite. */
function RecommendationCard({
  job,
  mentor,
  rating,
  limited,
  onProfile,
}: {
  job: Job
  mentor: RecommendedMentor
  rating: string | undefined
  limited: boolean
  onProfile: () => void
}) {
  return (
    <li className="flex flex-col gap-2.5 rounded-lg border border-border bg-card p-4">
      <div className="flex items-start gap-3">
        <MentorHeading mentor={mentor} />
      </div>
      <SignalChips
        signals={mentor.signals}
        extra={rating && <li className="rounded-full bg-muted px-2 py-0.5 text-caption">{rating}</li>}
      />
      <div className="mt-auto flex flex-wrap items-center justify-between gap-2">
        <ProfileLink mentor={mentor} onProfile={onProfile} />
        <InviteButton job={job} mentor={mentor} limited={limited} />
      </div>
    </li>
  )
}

/** "{average} sao ({count} lượt)", or "Mới" when asked and nothing counts; Ratings never reorder anything. */
function ratingText(summary: MentorSummary | undefined, withNewcomer: boolean): string | undefined {
  if (summary && summary.count > 0 && summary.average != null) {
    return t('screen.matching.find.rating', {
      average: summary.average.toLocaleString('vi-VN', { maximumFractionDigits: 1 }),
      count: summary.count,
    })
  }
  return withNewcomer && summary?.newcomer ? t('screen.matching.find.newcomer') : undefined
}

/**
 * "Mời chào giá" (FR-14, SG-41): no Confirmation dialog and no optimistic update; once the server confirms, the
 * button reads "Đã mời", and a user who bid already reads "Đã chào giá". Every one is disabled at a used-up limit.
 */
function InviteButton({ job, mentor, limited }: { job: Job; mentor: MentorResult; limited: boolean }) {
  const invite = useInvite(job.id)
  const toasts = useToasts()
  const hintId = useId()
  if (mentor.relation !== 'NONE') {
    return (
      <Button type="button" variant="secondary" size="compact" disabled>
        <Check aria-hidden="true" className="size-4" />
        {t(mentor.relation === 'INVITED' ? 'screen.matching.find.invited' : 'screen.matching.find.applied')}
      </Button>
    )
  }
  if (!mentor.available) {
    return (
      <div className="flex flex-col items-start gap-1">
        <Button type="button" variant="secondary" size="compact" disabled aria-describedby={hintId}>
          <Send aria-hidden="true" className="size-4" />
          {t('screen.matching.find.invite')}
        </Button>
        <p id={hintId} className="text-caption text-muted-foreground">
          {t('screen.matching.find.notLinked')}
        </p>
      </div>
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

/** PUB-4's content in the Sheet, with the no-contact note (UX-DR100). */
function SheetProfile({ username }: { username: string }) {
  return (
    <>
      <PersonProfileContent username={username} level={3} />
      <p className="text-caption text-muted-foreground">{t('screen.matching.find.noContact')}</p>
    </>
  )
}
