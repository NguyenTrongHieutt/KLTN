import { BriefcaseBusiness, ChevronRight, ListChecks, Package } from 'lucide-react'
import { cardInteractive, cardSurface } from '@/components/ui/card-surface'
import { cn } from '@/lib/utils'
import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { buttonVariants } from '@/components/ui/button-variants'
import { Card } from '@/components/ui/card'
import { EmptyState } from '@/components/ui/empty-state'
import { Page } from '@/components/ui/page'
import { useSession } from '@/features/identity/useSession'
import { JobStateBadge } from '@/features/job/JobStateBadge'
import { PhaseBadge } from '@/features/job/PhaseBadge'
import { needText } from '@/features/job/phaseText'
import { StageStrip } from '@/features/job/StageStrip'
import { audienceOf } from '@/features/job/jobStateText'
import { useJobs } from '@/features/job/jobApi'
import { roleLabel, useMyJobs, type MyJob } from '@/features/job/jobRoleApi'
import { useJobPostRecommendations } from '@/features/matching/recommendationApi'
import { useProducts } from '@/features/product/productApi'
import { productJobCounts, productJobsText } from '@/features/product/productJobs'
import { SourceStatusBadge } from '@/features/product/WorkflowSetup'
import { t } from '@/i18n/t'
import { useTodos } from './hooks'
import { TodoItemRow } from './TodoItemRow'

/** States in which a Job no longer runs for Tổng quan. */
const ENDED = ['CLOSED', 'CANCELLED']

const link = 'text-body-sm text-primary underline-offset-4 hover:underline'

const tile = cn('flex h-full flex-col gap-3 p-4 text-inherit no-underline md:p-5', cardSurface, cardInteractive)

/**
 * `/viec-can-lam`: CUS-1 "Tổng quan" (EXPERIENCE.md, "UX changes of 2026-11-23"), where a signed-in account lands.
 * The to-dos of every role the user holds come first, in the strip's order (FR-28, UX-DR14, UX-DR88); then the Jobs
 * still running, each with the user's role, its state and the 7-stage strip; then the user's Products with their
 * setup status, and the Jobs recommended to a user who can take work. Each region loads, fails and empties on its
 * own. From 1280 px the to-dos and the Jobs are the middle column, a feed one card wide, and the Products and the
 * recommendations a 320 px column on the right that stays in view (the layout of 2026-11-23).
 */
export function TodoListPage() {
  usePageTitle(t('screen.shared.overview.title'))
  return (
    <Page>
      <PageHeading inNavigation>{t('screen.shared.overview.heading')}</PageHeading>
      <div className="grid items-start gap-6 xl:grid-cols-[minmax(0,1fr)_320px]">
        <div className="flex min-w-0 flex-col gap-6">
          <Todos />
          <RunningJobs />
        </div>
        <div className="flex min-w-0 flex-col gap-6 xl:sticky xl:top-[calc(var(--spacing-header-height)+1.5rem)]">
          <Products />
          <Recommended />
        </div>
      </div>
    </Page>
  )
}

function SectionTitle({
  id,
  title,
  count,
  action,
  side = false,
}: {
  id: string
  title: string
  count?: number
  action?: ReactNode
  /** In the right column the title is one step smaller, so the column reads as secondary. */
  side?: boolean
}) {
  return (
    <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
      <h2 id={id} className={side ? 'text-h2 xl:text-h3' : 'text-h2'}>
        {title}
        {count !== undefined && (
          <span className="ms-2 font-normal text-muted-foreground">{t('screen.job.groupCount', { n: count })}</span>
        )}
      </h2>
      {action}
    </div>
  )
}

function Todos() {
  const todos = useTodos()
  return (
    <section aria-labelledby="overview-todos" className="flex flex-col gap-3">
      <SectionTitle id="overview-todos" title={t('screen.shared.overview.todos')} count={todos.data?.total} />
      <RegionState
        query={todos}
        skeleton={
          <div className="flex flex-col gap-3">
            {[0, 1].map((row) => (
              <Skeleton key={row} className="h-16 w-full" />
            ))}
          </div>
        }
      >
        {todos.data && todos.data.items.length > 0 ? (
          <ul aria-label={t('screen.shared.todo.listLabel')} className="flex flex-col gap-3">
            {todos.data.items.map((todo) => (
              <li key={todo.id}>
                <TodoItemRow todo={todo} />
              </li>
            ))}
          </ul>
        ) : (
          <EmptyState compact icon={ListChecks} title={t('screen.shared.todo.empty')} />
        )}
      </RegionState>
    </section>
  )
}

function RunningJobs() {
  const jobs = useMyJobs()
  const products = useProducts()
  const running = (jobs.data ?? []).filter((job) => !ENDED.includes(job.state) && !job.left)
  // With a Product and nothing running, the next step is to post a Job for it; finding work stays beside it.
  const list = products.data ?? []
  const postTo = list.length === 1 ? `/san-pham/${list[0].id}/cong-viec-moi` : '/san-pham'
  return (
    <section aria-labelledby="overview-jobs" className="flex flex-col gap-3">
      <SectionTitle
        id="overview-jobs"
        title={t('screen.shared.overview.running')}
        count={jobs.data ? running.length : undefined}
        action={
          <Link to="/cong-viec" className={link}>
            {t('screen.shared.overview.seeAllJobs')}
          </Link>
        }
      />
      <RegionState query={jobs} skeleton={<Skeleton className="h-24 w-full rounded-lg" />}>
        {running.length > 0 ? (
          <ul aria-label={t('screen.shared.overview.listJobs')} className="flex flex-col gap-3">
            {running.map((job) => (
              <li key={job.jobId}>
                <RunningJob job={job} />
              </li>
            ))}
          </ul>
        ) : (
          <EmptyState
            compact
            icon={BriefcaseBusiness}
            title={t('screen.shared.overview.runningEmpty')}
            description={list.length > 0 ? t('screen.shared.overview.runningEmptyHint') : undefined}
            action={
              <div className="flex flex-wrap justify-center gap-2">
                {list.length > 0 && (
                  <Link to={postTo} className={buttonVariants()}>
                    {t('screen.shared.overview.postJob')}
                  </Link>
                )}
                <Link to="/tim-viec" className={buttonVariants({ variant: 'secondary' })}>
                  {t('screen.shared.overview.findWork')}
                </Link>
              </div>
            }
          />
        )}
      </RegionState>
    </section>
  )
}

function RunningJob({ job }: { job: MyJob }) {
  return (
    <Link to={`/cong-viec/${job.jobId}`} className={tile}>
      <span className="flex flex-wrap items-start justify-between gap-2">
        <span className="flex min-w-0 flex-col gap-0.5">
          <span className="text-h3 break-words text-foreground">{job.title}</span>
          <span className="text-caption text-muted-foreground break-words">
            {/* A Crew member's row carries no Product name before the Job starts: no separator hanging in front. */}
            {[job.productName, roleLabel(job.role)].filter(Boolean).join(' · ')}
          </span>
        </span>
        <JobStateBadge state={job.state} audience={audienceOf(job.role)} />
      </span>
      <StageStrip state={job.state} compact />
    </Link>
  )
}

function Products() {
  const products = useProducts()
  const jobs = useJobs()
  const list = products.data ?? []
  return (
    <section aria-labelledby="overview-products" className="flex flex-col gap-3">
      <SectionTitle
        id="overview-products"
        side
        title={t('screen.shared.overview.products')}
        count={products.data ? list.length : undefined}
        action={
          <Link to="/san-pham" className={link}>
            {t('screen.shared.overview.seeAllProducts')}
          </Link>
        }
      />
      <RegionState query={products} skeleton={<Skeleton className="h-20 w-full rounded-lg" />}>
        {list.length > 0 ? (
          <ul aria-label={t('screen.shared.overview.listProducts')} className="grid gap-3 sm:grid-cols-2 xl:grid-cols-1">
            {list.slice(0, 6).map((product) => (
              <li key={product.id}>
                <Link to={`/san-pham/${product.id}`} className={tile}>
                  <span className="text-label font-semibold break-words text-foreground">{product.name}</span>
                  <span className="flex flex-wrap items-center gap-2">
                    <SourceStatusBadge status={product.sourceStatus} />
                    {jobs.data && (
                      <span className="text-caption text-muted-foreground">
                        {productJobsText(productJobCounts(jobs.data, product.id))}
                      </span>
                    )}
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        ) : (
          <EmptyState
            compact
            icon={Package}
            title={t('screen.shared.overview.productsEmpty')}
            action={
              <Link to="/san-pham/moi" className={buttonVariants({ variant: 'secondary' })}>
                {t('screen.shared.overview.registerProduct')}
              </Link>
            }
          />
        )}
      </RegionState>
    </section>
  )
}

/** How many recommended Jobs the right column lists before "Tìm việc". */
const RECOMMENDED = 4

/**
 * "Đề xuất cho bạn" (FR-14, AD-17) in the right column: the first Jobs Tìm việc would recommend, for a user who has
 * linked GitHub and so can take work. It is a shortcut, not a region of its own: with nothing to recommend, or
 * while the read fails, it is not shown, and Tìm việc keeps the whole list.
 */
function Recommended() {
  const verified = useSession().data?.account?.capabilities.githubVerified === true
  const posts = useJobPostRecommendations({ skills: [], categories: [] }, verified)
  const list = posts.data ?? []
  if (list.length === 0) {
    return null
  }
  return (
    <section aria-labelledby="overview-recommended" className="flex flex-col gap-3">
      <SectionTitle
        id="overview-recommended"
        side
        title={t('screen.jobPost.recommend.title')}
        action={
          <Link to="/tim-viec" className={link}>
            {t('screen.shared.overview.findWork')}
          </Link>
        }
      />
      <Card as="ul" padding="none" className="flex flex-col divide-y divide-border overflow-hidden">
        {list.slice(0, RECOMMENDED).map((post) => (
          <li key={post.id}>
            <Link
              to={`/tim-viec/${post.id}`}
              className="flex items-center gap-3 px-4 py-3 text-inherit no-underline hover:bg-muted/60"
            >
              <span className="flex min-w-0 grow flex-col gap-1">
                <span className="text-label font-semibold break-words">{post.title}</span>
                <span className="flex flex-wrap items-center gap-2">
                  <PhaseBadge phase={post.phase} />
                  {/* The phase badge already says a Mentor is sought; the line stays for the Crew's open places. */}
                  {post.need !== 'MENTOR' && (
                    <span className="text-caption text-muted-foreground">{needText(post.need, post.openSlots)}</span>
                  )}
                </span>
              </span>
              <ChevronRight aria-hidden="true" className="size-4.5 shrink-0 text-muted-foreground" />
            </Link>
          </li>
        ))}
      </Card>
    </section>
  )
}
