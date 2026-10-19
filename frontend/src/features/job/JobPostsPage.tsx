import { Search } from 'lucide-react'
import { useId } from 'react'
import { Link, useSearchParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { inputClass } from '@/components/formField-props'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { SkillPicker } from '@/components/SkillPicker'
import { categoryValue, splitSkillPickerValue } from '@/components/skillPickerValue'
import { Button } from '@/components/ui/button'
import { useSession } from '@/features/identity/useSession'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t, type MessageKey } from '@/i18n/t'
import { formatCalendarDayMonth, formatDayMonth } from '@/lib/dates'
import { cn } from '@/lib/utils'
import { CapacityBanners } from './CapacityBanners'
import { BUDGETS, JOB_TYPES, type Budget, type JobType } from './jobApi'
import { useJobPosts, useMentorCapacity, type JobPostFilter, type JobPostSummary } from './jobPostApi'

/** MEN-2's filters in the URL (UX-DR72): shareable, and kept by the browser's back button. */
const PARAMS = { type: 'loai', skills: 'ky-nang', categories: 'nhom-ky-nang', budget: 'ngan-sach', within: 'han', page: 'trang' }

function readFilter(params: URLSearchParams): JobPostFilter {
  const list = (key: string) => (params.get(key) ?? '').split(',').filter((value) => value !== '')
  const type = params.get(PARAMS.type)
  const budget = params.get(PARAMS.budget)
  const within = params.get(PARAMS.within)
  const page = Number.parseInt(params.get(PARAMS.page) ?? '1', 10)
  return {
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
  if (filter.type) params.set(PARAMS.type, filter.type)
  if (filter.skills.length > 0) params.set(PARAMS.skills, filter.skills.join(','))
  if (filter.categories.length > 0) params.set(PARAMS.categories, filter.categories.join(','))
  if (filter.budget) params.set(PARAMS.budget, filter.budget)
  if (filter.within) params.set(PARAMS.within, String(filter.within))
  if (filter.page > 0) params.set(PARAMS.page, String(filter.page + 1))
  return params
}

function filtered(filter: JobPostFilter): boolean {
  return !!(filter.type || filter.skills.length > 0 || filter.categories.length > 0 || filter.budget || filter.within)
}

/**
 * MEN-2 Tìm việc (`/tim-viec`, FR-12, UX-DR72, UX-DR78) for approved Mentors: Open Job Posts, 20 a
 * page, newest first, with Job Type, Skill (Skills or whole Categories), budget, and deadline filters
 * that apply at once and live in the URL, and the FR-13 banners.
 */
export function JobPostsPage() {
  usePageTitle(t('screen.jobPost.title'))
  const account = useSession().data?.account
  if (account && (account.role !== 'MENTOR' || account.mentorApprovalStatus !== 'APPROVED')) {
    return <NotFoundScreen />
  }
  return <JobPostList />
}

function JobPostList() {
  const [params, setParams] = useSearchParams()
  const filter = readFilter(params)
  const posts = useJobPosts(filter)
  const capacity = useMentorCapacity()
  const update = (next: Partial<JobPostFilter>) => setParams(writeFilter({ ...filter, page: 0, ...next }), { replace: true })
  const data = posts.data
  return (
    <div className="mx-auto flex max-w-[1000px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <div className="flex flex-col gap-1">
        <PageHeading>{t('screen.jobPost.heading')}</PageHeading>
        <p className="text-body-sm text-muted-foreground">{t('screen.jobPost.lead')}</p>
      </div>
      <CapacityBanners capacity={capacity.data} />
      <Filters filter={filter} update={update} />
      <RegionState query={posts} skeleton={<Skeleton className="h-64 w-full" />}>
        {data &&
          (data.total === 0 ? (
            filtered(filter) ? (
              <div className="flex flex-col items-center gap-3 rounded-lg border border-border bg-card p-10 text-center">
                <p className="text-label font-semibold">{t('screen.jobPost.noMatch')}</p>
                <p className="text-body-sm text-muted-foreground">{t('screen.jobPost.noMatchBody')}</p>
                <Button type="button" variant="secondary" onClick={() => setParams(new URLSearchParams(), { replace: true })}>
                  {t('screen.jobPost.clearFilters')}
                </Button>
              </div>
            ) : (
              <div className="flex flex-col items-center gap-3 rounded-lg border border-border bg-card p-12 text-center">
                <Search aria-hidden="true" className="size-10 text-muted-foreground" />
                <p className="text-label">{t('screen.jobPost.empty')}</p>
              </div>
            )
          ) : (
            <section aria-labelledby="job-posts" className="flex flex-col gap-3">
              <h2 id="job-posts" className="text-h2">
                {t('screen.jobPost.listHeading')}{' '}
                <span className="font-normal text-muted-foreground">{t('screen.jobPost.count', { n: data.total })}</span>
              </h2>
              <ul className="flex flex-col divide-y divide-border rounded-lg border border-border bg-card">
                {data.items.map((post) => (
                  <PostItem key={post.id} post={post} />
                ))}
              </ul>
              <Pager page={data.page} pages={Math.ceil(data.total / data.size)} go={(page) => setParams(writeFilter({ ...filter, page }))} />
            </section>
          ))}
      </RegionState>
    </div>
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

function PostItem({ post }: { post: JobPostSummary }) {
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
        {post.deadline && (
          <span className="rounded-full bg-muted px-2 py-0.5 text-caption">
            {t('screen.jobPost.due', { date: formatCalendarDayMonth(post.deadline) })}
          </span>
        )}
      </Link>
    </li>
  )
}

/** "Trước", the pages around the current one, and "Sau". */
function Pager({ page, pages, go }: { page: number; pages: number; go: (page: number) => void }) {
  if (pages <= 1) {
    return null
  }
  const from = Math.max(0, Math.min(page - 2, pages - 5))
  const shown = Array.from({ length: Math.min(5, pages) }, (_, index) => from + index)
  return (
    <nav aria-label={t('screen.jobPost.pages')} className="flex flex-wrap justify-center gap-1">
      <Button type="button" variant="secondary" disabled={page === 0} onClick={() => go(page - 1)}>
        {t('screen.jobPost.previous')}
      </Button>
      {shown.map((number) => (
        <Button
          key={number}
          type="button"
          variant={number === page ? 'primary' : 'ghost'}
          aria-current={number === page ? 'page' : undefined}
          aria-label={t('screen.jobPost.page', { n: number + 1 })}
          className={cn(number === page && 'pointer-events-none')}
          onClick={() => go(number)}
        >
          {number + 1}
        </Button>
      ))}
      <Button type="button" variant="secondary" disabled={page >= pages - 1} onClick={() => go(page + 1)}>
        {t('screen.jobPost.next')}
      </Button>
    </nav>
  )
}
