import { FileText } from 'lucide-react'
import { Link, useParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { useSession } from '@/features/identity/useSession'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t, type MessageKey } from '@/i18n/t'
import { formatCalendarDayMonth, formatDayMonth } from '@/lib/dates'
import { formatSize } from '@/lib/fileSize'
import { isNotFound } from '@/lib/problemMessage'
import { CapacityBanners } from './CapacityBanners'
import { JobStateBadge } from './JobStateBadge'
import { useCustomerRating, useJobPost, useMentorCapacity, type JobPost, type MentorCapacity } from './jobPostApi'

/**
 * MEN-3 Chi tiết tin công việc (`/tim-viec/{jobId}`, FR-12, FR-13) for approved Mentors: the Job
 * Post's answers, Job Types, Skills with their plain hints, deadline, budget, and screenshots; the
 * Customer's Rating summary, never their name or contact details; the Product; the Team's free
 * Students; and the FR-13 banners. Applying arrives with Story 3.6.
 */
export function JobPostPage() {
  const { postId } = useParams()
  const post = useJobPost(postId)
  const account = useSession().data?.account
  usePageTitle(post.data?.title ?? t('screen.jobPost.title'))
  if (
    (account && (account.role !== 'MENTOR' || account.mentorApprovalStatus !== 'APPROVED')) ||
    (post.isError && isNotFound(post.error))
  ) {
    return <NotFoundScreen />
  }
  return (
    <div className="mx-auto flex max-w-[1120px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <RegionState query={post} skeleton={<Skeleton className="h-96 w-full" />}>
        {post.data && <Post post={post.data} />}
      </RegionState>
    </div>
  )
}

function Post({ post }: { post: JobPost }) {
  const capacity = useMentorCapacity()
  const types = post.jobTypes.map((type) => t(`term.jobType${type}.label` as MessageKey)).join(', ')
  const meta = [
    t('screen.jobPost.detail.meta', { product: post.productName, types }),
    post.budget && t(`screen.job.form.budget${post.budget}` as MessageKey),
    post.deadline && t('screen.jobPost.detail.deadline', { date: formatCalendarDayMonth(post.deadline) }),
    t('screen.jobPost.detail.opened', { date: formatDayMonth(post.openedAt) }),
  ].filter((part): part is string => !!part)
  const answers = [
    ['problem', post.problem],
    ['expected', post.expected],
    ['appUsers', post.appUsers],
    ['doneCheck', post.doneCheck],
  ] as const
  return (
    <>
      <div className="flex flex-col gap-2">
        <nav aria-label={t('screen.jobPost.detail.breadcrumb')} className="text-caption text-muted-foreground">
          <Link to="/tim-viec" className="text-primary underline-offset-4 hover:underline">
            {t('screen.jobPost.detail.breadcrumb')}
          </Link>{' '}
          / <span className="break-words">{post.title}</span>
        </nav>
        <div className="flex flex-wrap items-center gap-2">
          <PageHeading>{post.title}</PageHeading>
          <JobStateBadge state="OPEN" audience="mentor" />
        </div>
        <p className="text-caption text-muted-foreground break-words">{meta.join(' · ')}</p>
      </div>
      <CapacityBanners capacity={capacity.data} />
      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_300px]">
        <section aria-label={post.title} className="flex min-w-0 flex-col gap-4 rounded-lg border border-border bg-card p-5">
          {answers.map(
            ([field, answer]) =>
              answer && (
                <div key={field} className="flex flex-col gap-1 text-body-sm">
                  <p className="text-label font-semibold">{t(`screen.jobPost.detail.${field}`)}</p>
                  <p className="break-words whitespace-pre-line">{answer}</p>
                </div>
              ),
          )}
          {post.skills.length > 0 && (
            <div className="flex flex-col gap-1.5">
              <p className="text-label font-semibold">{t('screen.jobPost.detail.skills')}</p>
              <ul className="flex flex-col gap-1.5">
                {post.skills.map((skill) => (
                  <li key={skill.id} className="flex flex-wrap items-baseline gap-2 text-body-sm">
                    <span className="rounded-full bg-muted px-2 py-0.5 text-caption font-semibold">{skill.name}</span>
                    {skill.hint && <span className="text-caption text-muted-foreground">{skill.hint}</span>}
                  </li>
                ))}
              </ul>
            </div>
          )}
          {post.screenshots.length > 0 && (
            <div className="flex flex-col gap-1.5">
              <p className="text-label font-semibold">{t('screen.jobPost.detail.screenshots')}</p>
              <ul className="flex flex-col gap-2">
                {post.screenshots.map((shot) => (
                  <li key={shot.fileId} className="flex items-center gap-3 rounded-md border border-border px-4 py-2.5">
                    <FileText aria-hidden="true" className="size-5 shrink-0 text-muted-foreground" />
                    <a
                      href={shot.url}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="text-body-sm break-all text-primary underline-offset-4 hover:underline"
                    >
                      {shot.fileName} · {formatSize(shot.sizeBytes)}
                    </a>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </section>
        <aside className="flex flex-col gap-4">
          <CustomerCard postId={post.id} />
          <section aria-labelledby="post-product" className="flex flex-col gap-1.5 rounded-lg border border-border bg-card p-4 text-body-sm">
            <h3 id="post-product" className="text-h3">
              {t('screen.jobPost.detail.product')}
            </h3>
            <p className="break-words">
              {t('screen.jobPost.detail.builtWith', {
                product: post.productName,
                tools: post.productTools.map((tool) => t(`screen.product.tools.${tool}` as MessageKey)).join(', '),
              })}
            </p>
            <p className="break-words whitespace-pre-line text-muted-foreground">{post.productDescription}</p>
            <p className="text-muted-foreground">
              {post.productOpenFindings > 0
                ? t('screen.jobPost.detail.scanFindings', { n: post.productOpenFindings })
                : t('screen.jobPost.detail.scanClean')}
            </p>
          </section>
          {capacity.data && <TeamCard capacity={capacity.data} />}
        </aside>
      </div>
    </>
  )
}

/** The Customer's visible Rating summary (AD-33); a Job Post never names its Customer. */
function CustomerCard({ postId }: { postId: string }) {
  const rating = useCustomerRating(postId)
  return (
    <section aria-labelledby="post-customer" className="flex flex-col gap-1.5 rounded-lg border border-border bg-card p-4 text-body-sm">
      <h3 id="post-customer" className="text-h3">
        {t('screen.jobPost.detail.customer')}
      </h3>
      {rating.data &&
        (rating.data.count > 0 && rating.data.average != null ? (
          <p>
            <span className="font-semibold">
              {t('screen.jobPost.detail.rating', {
                average: rating.data.average.toLocaleString('vi-VN', { maximumFractionDigits: 1 }),
              })}
            </span>{' '}
            <span className="text-muted-foreground">{t('screen.jobPost.detail.ratingCount', { n: rating.data.count })}</span>
          </p>
        ) : (
          <p className="text-muted-foreground">{t('screen.jobPost.detail.noRating')}</p>
        ))}
    </section>
  )
}

function TeamCard({ capacity }: { capacity: MentorCapacity }) {
  return (
    <section aria-labelledby="post-team" className="flex flex-col gap-1.5 rounded-lg border border-border bg-card p-4 text-body-sm">
      <h3 id="post-team" className="text-h3">
        {t('screen.jobPost.detail.team')}
      </h3>
      <p>
        {capacity.freeStudents > 0
          ? t('screen.jobPost.detail.teamFree', { n: capacity.teamSize, k: capacity.freeStudents })
          : t('screen.jobPost.detail.teamNoneFree', { n: capacity.teamSize })}
      </p>
      <Link to="/nhom" className="w-fit text-primary underline-offset-4 hover:underline">
        {t('screen.jobPost.detail.viewTeam')}
      </Link>
    </section>
  )
}
