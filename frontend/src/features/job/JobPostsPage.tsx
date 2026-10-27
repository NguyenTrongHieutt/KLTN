import { Search, X } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { inputClass } from '@/components/formField-props'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { Pager } from '@/components/Pager'
import { SkillPicker } from '@/components/SkillPicker'
import { categoryValue, splitSkillPickerValue } from '@/components/skillPickerValue'
import { Button } from '@/components/ui/button'
import { holdsOtherRole } from '@/features/identity/api'
import { useSession } from '@/features/identity/useSession'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t, type MessageKey } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatCalendarDayMonth, formatDayMonth } from '@/lib/dates'
import { cn } from '@/lib/utils'
import { isLive, useMyApplications } from '@/features/matching/applicationApi'
import { useJobPostRecommendations, type RecommendedPost } from '@/features/matching/recommendationApi'
import { SignalChips } from '@/features/matching/SignalChips'
import { CapacityBanners } from './CapacityBanners'
import { FindWorkGate } from './FindWorkGate'
import { BUDGETS, JOB_TYPES, type Budget, type JobType } from './jobApi'
import {
  useJobPosts,
  useLastJobPosts,
  useMentorCapacity,
  type JobPostFilter,
  type JobPostSummary,
} from './jobPostApi'

/** MEN-2's keyword and filters in the URL (UX-DR47, UX-DR72): shareable, and kept by the back button. */
const PARAMS = {
  q: 'q',
  type: 'loai',
  skills: 'ky-nang',
  categories: 'nhom-ky-nang',
  budget: 'ngan-sach',
  within: 'han',
  page: 'trang',
}

/** The Search bar's limit (EXPERIENCE.md, Search bar). */
const MAX_KEYWORD = 100

function readFilter(params: URLSearchParams): JobPostFilter {
  const list = (key: string) => (params.get(key) ?? '').split(',').filter((value) => value !== '')
  const q = (params.get(PARAMS.q) ?? '').trim().slice(0, MAX_KEYWORD)
  const type = params.get(PARAMS.type)
  const budget = params.get(PARAMS.budget)
  const within = params.get(PARAMS.within)
  const page = Number.parseInt(params.get(PARAMS.page) ?? '1', 10)
  return {
    q: q === '' ? undefined : q,
    type: JOB_TYPES.includes(type as JobType) ? (type as JobType) : undefined,
    skills: list(PARAMS.skills),
    categories: list(PARAMS.categories),
    budget: BUDGETS.includes(budget as Budget) ? (budget as Budget) : undefined,
    within: within === '14' ? 14 : within === '30' ? 30 : undefined,
    page: Number.isFinite(page) && page > 1 ? page - 1 : 0,
  }
}

function writeFilter(filter: JobPostFilter): URLSearchParams {
  const params = new URLSearchParams()
  if (filter.q) params.set(PARAMS.q, filter.q)
  if (filter.type) params.set(PARAMS.type, filter.type)
  if (filter.skills.length > 0) params.set(PARAMS.skills, filter.skills.join(','))
  if (filter.categories.length > 0) params.set(PARAMS.categories, filter.categories.join(','))
  if (filter.budget) params.set(PARAMS.budget, filter.budget)
  if (filter.within) params.set(PARAMS.within, String(filter.within))
  if (filter.page > 0) params.set(PARAMS.page, String(filter.page + 1))
  return params
}

/** Whether a filter other than the keyword is set. */
function filtered(filter: JobPostFilter): boolean {
  return !!(filter.type || filter.skills.length > 0 || filter.categories.length > 0 || filter.budget || filter.within)
}

/**
 * MEN-2 Tìm việc (`/tim-viec`, FR-12, UX-DR47, UX-DR72, UX-DR78) for Mentors, with no approval: Open Job
 * Posts, 20 a page, newest first, or by relevance to a keyword that only Enter or "Tìm" runs; Job
 * Type, Skill (Skills or whole Categories), budget, and deadline filters that apply at once; all of
 * it in the URL; and the FR-13 banners. Without GitHub linked, the GitHub gate stands in for all of it (X1).
 */
export function JobPostsPage() {
  usePageTitle(t('screen.jobPost.title'))
  const account = useSession().data?.account
  if (account && holdsOtherRole(account, 'MENTOR')) {
    return <NotFoundScreen />
  }
  if (account && !account.capabilities.githubVerified) {
    return <FindWorkGate returnTo="/tim-viec" />
  }
  return <JobPostList />
}

function JobPostList() {
  const [params, setParams] = useSearchParams()
  const filter = readFilter(params)
  const posts = useJobPosts(filter)
  const last = useLastJobPosts()
  const capacity = useMentorCapacity()
  const recommendations = useJobPostRecommendations(
    { skills: filter.skills, categories: filter.categories },
    filter.q === undefined,
  )
  const update = (next: Partial<JobPostFilter>) => setParams(writeFilter({ ...filter, page: 0, ...next }), { replace: true })
  // Searching too fast keeps the earlier results on screen (UX-DR27).
  const limited = posts.error instanceof ApiError && posts.error.status === 429 ? posts.error : undefined
  const results = (data: NonNullable<typeof posts.data>) => (
    <Results
      data={data}
      filter={filter}
      clearFilters={() => update({ type: undefined, skills: [], categories: [], budget: undefined, within: undefined })}
      clearAll={() => setParams(new URLSearchParams(), { replace: true })}
      go={(page) => setParams(writeFilter({ ...filter, page }))}
    />
  )
  return (
    <div className="mx-auto flex max-w-[1000px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <div className="flex flex-col gap-1">
        <PageHeading>{t('screen.jobPost.heading')}</PageHeading>
        <p className="text-body-sm text-muted-foreground">{t('screen.jobPost.lead')}</p>
      </div>
      <CapacityBanners capacity={capacity.data} />
      <SearchBar
        key={filter.q ?? ''}
        keyword={filter.q}
        retryAfter={limited ? (limited.retryAfter ?? 60) : undefined}
        search={(q) => update({ q })}
      />
      <Filters filter={filter} update={update} />
      {filter.q === undefined && recommendations.data && recommendations.data.length > 0 && (
        <Recommended posts={recommendations.data} />
      )}
      {limited && last.data ? (
        results(last.data)
      ) : (
        <RegionState query={posts} skeleton={<Skeleton className="h-64 w-full" />}>
          {posts.data && results(posts.data)}
        </RegionState>
      )}
    </div>
  )
}

/**
 * The Search bar (UX-DR47): only Enter or "Tìm" runs it, "Tìm" waits for a keyword, input stops at
 * 100 characters, and "Xóa từ khóa" returns to the default view. Searching too fast replaces the
 * helper with the wait from `Retry-After`.
 */
function SearchBar({
  keyword,
  retryAfter,
  search,
}: {
  keyword: string | undefined
  retryAfter: number | undefined
  search: (q: string | undefined) => void
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
        {t('screen.jobPost.searchLabel')}
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
            placeholder={t('screen.jobPost.searchPlaceholder')}
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
        {retryAfter ? t('screen.jobPost.tooFast', { seconds: retryAfter }) : t('screen.jobPost.searchHelper')}
      </p>
    </form>
  )
}

function Results({
  data,
  filter,
  clearFilters,
  clearAll,
  go,
}: {
  data: { items: JobPostSummary[]; page: number; size: number; total: number }
  filter: JobPostFilter
  clearFilters: () => void
  clearAll: () => void
  go: (page: number) => void
}) {
  const mine = useMyApplications()
  const applied = new Set((mine.data ?? []).filter(isLive).map((application) => application.jobId))
  if (data.total === 0) {
    if (filter.q) {
      return (
        <div className="flex flex-col items-center gap-3 rounded-lg border border-border bg-card p-10 text-center">
          <p aria-live="polite" className="text-label font-semibold break-words">
            {t('screen.jobPost.noMatchKeyword', { q: filter.q })}
          </p>
          <p className="text-body-sm text-muted-foreground">{t('screen.jobPost.noMatchKeywordBody')}</p>
          {filtered(filter) && (
            <Button type="button" variant="secondary" onClick={clearFilters}>
              {t('screen.jobPost.clearFilters')}
            </Button>
          )}
        </div>
      )
    }
    return filtered(filter) ? (
      <div className="flex flex-col items-center gap-3 rounded-lg border border-border bg-card p-10 text-center">
        <p className="text-label font-semibold">{t('screen.jobPost.noMatch')}</p>
        <p className="text-body-sm text-muted-foreground">{t('screen.jobPost.noMatchBody')}</p>
        <Button type="button" variant="secondary" onClick={clearAll}>
          {t('screen.jobPost.clearFilters')}
        </Button>
      </div>
    ) : (
      <div className="flex flex-col items-center gap-3 rounded-lg border border-border bg-card p-12 text-center">
        <Search aria-hidden="true" className="size-10 text-muted-foreground" />
        <p className="text-label">{t('screen.jobPost.empty')}</p>
      </div>
    )
  }
  return (
    <section aria-labelledby="job-posts" className="flex flex-col gap-3">
      {filter.q ? (
        <div className="flex flex-col gap-0.5">
          <h2 id="job-posts" aria-live="polite" className="text-h2 break-words">
            {t('screen.jobPost.resultsHeading', { q: filter.q, n: data.total })}
          </h2>
          <p className="text-caption text-muted-foreground">{t('screen.jobPost.resultsNote')}</p>
        </div>
      ) : (
        <h2 id="job-posts" className="text-h2">
          {t('screen.jobPost.listHeading')}{' '}
          <span className="font-normal text-muted-foreground">{t('screen.jobPost.count', { n: data.total })}</span>
        </h2>
      )}
      <ul className="flex flex-col divide-y divide-border rounded-lg border border-border bg-card">
        {data.items.map((post) => (
          <PostItem key={post.id} post={post} applied={applied.has(post.id)} />
        ))}
      </ul>
      <Pager page={data.page} pages={Math.ceil(data.total / data.size)} go={go} />
    </section>
  )
}

function Filters({ filter, update }: { filter: JobPostFilter; update: (next: Partial<JobPostFilter>) => void }) {
  const ids = useId()
  const skills = [...filter.categories.map(categoryValue), ...filter.skills]
  return (
    <div role="group" aria-label={t('screen.jobPost.filters')} className="flex flex-col gap-3 rounded-lg border border-border bg-card px-4 py-3">
      <div className="flex flex-wrap items-end gap-3">
        <div className="flex min-w-40 flex-[1_1_170px] flex-col gap-1">
          <label htmlFor={`${ids}-type`} className="text-caption">
            {t('screen.jobPost.type')}
          </label>
          <select
            id={`${ids}-type`}
            value={filter.type ?? ''}
            onChange={(event) => update({ type: (event.target.value || undefined) as JobType | undefined })}
            className={inputClass}
          >
            <option value="">{t('screen.jobPost.typeAll')}</option>
            {JOB_TYPES.map((type) => (
              <option key={type} value={type}>
                {t(`term.jobType${type}.label` as MessageKey)}
              </option>
            ))}
          </select>
        </div>
        <div className="flex min-w-60 flex-[3_1_360px] flex-col gap-1">
          <label htmlFor={`${ids}-skills`} className="text-caption">
            {t('screen.jobPost.skills')}
          </label>
          <SkillPicker
            id={`${ids}-skills`}
            value={skills}
            onChange={(values) => update(splitSkillPickerValue(values))}
            max={20}
            wholeCategories
            describedBy={`${ids}-hint`}
          />
        </div>
        <div className="flex min-w-36 flex-[1_1_150px] flex-col gap-1">
          <label htmlFor={`${ids}-budget`} className="text-caption">
            {t('screen.jobPost.budget')}
          </label>
          <select
            id={`${ids}-budget`}
            value={filter.budget ?? ''}
            onChange={(event) => update({ budget: (event.target.value || undefined) as Budget | undefined })}
            className={inputClass}
          >
            <option value="">{t('screen.jobPost.budgetAll')}</option>
            {BUDGETS.map((budget) => (
              <option key={budget} value={budget}>
                {t(`screen.job.form.budget${budget}` as MessageKey)}
              </option>
            ))}
          </select>
        </div>
        <div className="flex min-w-36 flex-[1_1_150px] flex-col gap-1">
          <label htmlFor={`${ids}-within`} className="text-caption">
            {t('screen.jobPost.within')}
          </label>
          <select
            id={`${ids}-within`}
            value={filter.within ? String(filter.within) : ''}
            onChange={(event) =>
              update({ within: event.target.value === '14' ? 14 : event.target.value === '30' ? 30 : undefined })
            }
            className={inputClass}
          >
            <option value="">{t('screen.jobPost.withinAll')}</option>
            <option value="14">{t('screen.jobPost.within14')}</option>
            <option value="30">{t('screen.jobPost.within30')}</option>
          </select>
        </div>
      </div>
      <p id={`${ids}-hint`} className="text-caption text-muted-foreground">
        {t('screen.jobPost.filtersHint')}
      </p>
    </div>
  )
}

/**
 * "Đề xuất cho bạn" (FR-14, AD-17, UX-DR47): Job Posts not yet applied to, by shared Skills, then
 * Job Types from the Mentor's work history, then newest; shown only without a keyword. A Customer's
 * invitation reads "Bạn được mời".
 */
function Recommended({ posts }: { posts: RecommendedPost[] }) {
  return (
    <section aria-labelledby="recommended-posts" className="flex flex-col gap-3">
      <h2 id="recommended-posts" className="text-h2">
        {t('screen.jobPost.recommend.title')}
      </h2>
      <ul className="grid gap-3 md:grid-cols-2">
        {posts.map((post) => {
          const types = post.jobTypes.map((type) => t(`term.jobType${type}.label` as MessageKey)).join(', ')
          return (
            <li key={post.id}>
              <Link
                to={`/tim-viec/${post.id}`}
                className="flex h-full flex-col gap-2 rounded-lg border border-border bg-card p-4 text-inherit no-underline hover:bg-muted/50"
              >
                <span className="flex flex-wrap items-start justify-between gap-2">
                  <span className="text-h3 break-words">{post.title}</span>
                  {post.invited && (
                    <span className="rounded-full bg-warning-soft px-2 py-0.5 text-caption font-semibold text-warning">
                      {t('screen.jobPost.recommend.invited')}
                    </span>
                  )}
                </span>
                <span className="text-caption text-muted-foreground break-words">
                  {post.budget
                    ? t('screen.jobPost.meta', {
                        product: post.productName,
                        types,
                        budget: t(`screen.job.form.budget${post.budget}` as MessageKey),
                        date: formatDayMonth(post.openedAt),
                      })
                    : t('screen.jobPost.metaNoBudget', { product: post.productName, types, date: formatDayMonth(post.openedAt) })}
                </span>
                <SignalChips signals={post.signals} />
              </Link>
            </li>
          )
        })}
      </ul>
    </section>
  )
}

function PostItem({ post, applied }: { post: JobPostSummary; applied: boolean }) {
  const types = post.jobTypes.map((type) => t(`term.jobType${type}.label` as MessageKey)).join(', ')
  return (
    <li>
      <Link
        to={`/tim-viec/${post.id}`}
        className="flex flex-wrap items-center gap-3 px-4 py-3 text-inherit no-underline hover:bg-muted/50"
      >
        <span className="flex min-w-60 grow flex-col gap-1">
          <span className="text-label font-semibold break-words">{post.title}</span>
          <span className="text-caption text-muted-foreground break-words">
            {post.budget
              ? t('screen.jobPost.meta', {
                  product: post.productName,
                  types,
                  budget: t(`screen.job.form.budget${post.budget}` as MessageKey),
                  date: formatDayMonth(post.openedAt),
                })
              : t('screen.jobPost.metaNoBudget', { product: post.productName, types, date: formatDayMonth(post.openedAt) })}
          </span>
          {post.skills.length > 0 && (
            <span className="flex flex-wrap gap-1.5">
              {post.skills.map((skill) => (
                <span key={skill} className="rounded-full bg-muted px-2 py-0.5 text-caption">
                  {skill}
                </span>
              ))}
            </span>
          )}
        </span>
        {applied ? (
          <span className="rounded-full bg-success-soft px-2 py-0.5 text-caption font-semibold text-success">
            {t('screen.matching.appliedBadge')}
          </span>
        ) : (
          post.deadline && (
            <span className="rounded-full bg-muted px-2 py-0.5 text-caption">
              {t('screen.jobPost.due', { date: formatCalendarDayMonth(post.deadline) })}
            </span>
          )
        )}
      </Link>
    </li>
  )
}
