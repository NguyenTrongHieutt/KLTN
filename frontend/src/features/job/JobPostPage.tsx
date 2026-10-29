import { FileText, Info } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link, useParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { buttonVariants } from '@/components/ui/button-variants'
import { ReportAbuseMenu } from '@/features/abuse/ReportAbuseMenu'
import { personPath } from '@/features/identity/personText'
import { useSession } from '@/features/identity/useSession'
import { rich } from '@/i18n/rich'
import { t, type MessageKey } from '@/i18n/t'
import { formatCalendarDayMonth, formatDayMonth, formatMonthYear } from '@/lib/dates'
import { formatSize } from '@/lib/fileSize'
import { isNotFound } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { BidPanel } from '@/features/matching/BidPanel'
import { useMentorInvitation, type MentorInvitation } from '@/features/matching/invitationApi'
import { FindWorkGate } from './FindWorkGate'
import { leftEarly, roleLabel, useJobRole, useMyJobs, youAreText, type JobRoleView } from './jobRoleApi'
import { useCustomerRating, useJobPost, type JobPost } from './jobPostApi'
import { PhaseBadge } from './PhaseBadge'
import { needText } from './phaseText'

/**
 * MEN-3 Chi tiết công việc (`/tim-viec/{jobId}`, FR-12, FR-13, UX-DR98) for every user: a listed Job's Post (the
 * answers, Job Types, Skills with their plain hints, deadline, budget, and screenshots), its phase and what it
 * needs, the Customer's kind, month joined and Rating summary (never their name or contact details), and the
 * Product. One action area follows the phase and the user's relation to the Job: "Chào giá làm người hướng dẫn",
 * which opens FND-1, or the user's bid with "Xem" and "Rút", while the Job looks for a Mentor (Story 21.2); the
 * "Xin vào nhóm" details while its Mentor recruits (Story 21.4 adds the button); and for the Job's own people
 * "Bạn là {role} của công việc này" with the way to the Job. A Job no longer listed shows the plain "no longer open" gate, or the role notice to its
 * own people, or a lapsed invitation's card (FR-14, UX-DR27, UX-DR95). Without GitHub linked, the GitHub gate stands
 * in for the Job Post (X1).
 */
export function JobPostPage() {
  const { postId } = useParams()
  const account = useSession().data?.account
  if (account && !account.capabilities.githubVerified) {
    return <GatedJobPost returnTo={`/tim-viec/${postId ?? ''}`} />
  }
  return <JobPostDetail postId={postId} />
}

/** MEN-3 behind the GitHub gate, titled as the page. */
function GatedJobPost({ returnTo }: { returnTo: string }) {
  usePageTitle(t('screen.jobPost.title'))
  return <FindWorkGate returnTo={returnTo} />
}

function JobPostDetail({ postId }: { postId: string | undefined }) {
  const post = useJobPost(postId)
  const invitation = useMentorInvitation(postId)
  const role = useJobRole(postId)
  usePageTitle(post.data?.title ?? invitation.data?.jobTitle ?? t('screen.jobPost.title'))
  if (post.isError && isNotFound(post.error)) {
    if (invitation.isPending || role.isPending) {
      return <Skeleton className="m-6 h-48" />
    }
    if (role.data && postId) {
      return <RoleOnly jobId={postId} role={role.data} />
    }
    return invitation.data?.status === 'LAPSED' ? <LapsedInvitation invitation={invitation.data} /> : <NoLongerOpen />
  }
  return (
    <div className="mx-auto flex max-w-[1120px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <RegionState query={post} skeleton={<Skeleton className="h-96 w-full" />}>
        {post.data && <Post post={post.data} invitation={invitation.data ?? undefined} />}
      </RegionState>
    </div>
  )
}

/** A page of MEN-3 with nothing but a breadcrumb back to "Tìm việc" and its content. */
function GatePage({ title, children }: { title?: string; children: ReactNode }) {
  return (
    <div className="mx-auto flex max-w-[1120px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <nav aria-label={t('screen.jobPost.detail.breadcrumb')} className="text-caption text-muted-foreground">
        <Link to="/tim-viec" className="text-primary underline-offset-4 hover:underline">
          {t('screen.jobPost.detail.breadcrumb')}
        </Link>
        {title && (
          <>
            {' '}
            / <span className="break-words">{title}</span>
          </>
        )}
      </nav>
      {children}
    </div>
  )
}

/** "Lời mời này đã hết hiệu lực…" with "Tìm việc khác" (MEN-3, FR-14). */
function LapsedInvitation({ invitation }: { invitation: MentorInvitation }) {
  return (
    <GatePage title={invitation.jobTitle}>
      <div role="status" className="flex flex-col items-center gap-3 rounded-lg border border-border bg-card px-6 py-14 text-center">
        <PageHeading className="text-h3">{t('screen.jobPost.invitation.lapsedTitle')}</PageHeading>
        <p className="text-body-sm text-muted-foreground">{t('screen.jobPost.invitation.lapsedBody')}</p>
        <Link to="/tim-viec" className={buttonVariants()}>
          {t('screen.jobPost.invitation.findOther')}
        </Link>
      </div>
    </GatePage>
  )
}

/** The plain "no longer open" gate (UX-DR95): a Job "Tìm việc" no longer lists, with "Tìm việc khác". */
function NoLongerOpen() {
  return (
    <GatePage>
      <div role="status" className="flex flex-col items-center gap-3 rounded-lg border border-border bg-card px-6 py-14 text-center">
        <PageHeading className="text-h3">{t('screen.jobPost.detail.goneTitle')}</PageHeading>
        <p className="text-body-sm text-muted-foreground">{t('screen.jobPost.detail.goneBody')}</p>
        <Link to="/tim-viec" className={buttonVariants()}>
          {t('screen.jobPost.detail.findOther')}
        </Link>
      </div>
    </GatePage>
  )
}

/** A Job no longer listed, opened by one of its own people: their role, with the way to the Job (UX-DR98). */
function RoleOnly({ jobId, role }: { jobId: string; role: JobRoleView }) {
  return (
    <GatePage>
      <PageHeading className="sr-only">{t('screen.jobPost.title')}</PageHeading>
      <RoleNotice jobId={jobId} role={role} />
    </GatePage>
  )
}

/**
 * "Bạn là {role} của công việc này" with "Mở công việc" (JOB-1), in place of an action (AD-43, UX-DR98); a member who
 * left the running Job reads "Bạn là: Thành viên nhóm · đã rời" (UX-DR87, UX-DR104), the Job's state read from
 * "Công việc" as JOB-H reads it.
 */
function RoleNotice({ jobId, role }: { jobId: string; role: JobRoleView }) {
  const mine = useMyJobs(role.crewKind === 'FORMER')
  const state = mine.data?.find((job) => job.jobId === jobId)?.state
  return (
    <div role="status" className="flex flex-col gap-3 rounded-lg border border-border bg-card p-5">
      <p className="text-label font-semibold">
        {leftEarly(role, state)
          ? youAreText(role, state)
          : t('screen.jobPost.detail.role', { role: roleLabel(role.role) })}
      </p>
      <Link to={`/cong-viec/${jobId}`} className={cn(buttonVariants({ variant: 'secondary' }), 'w-fit')}>
        {t('screen.jobPost.detail.openJob')}
      </Link>
    </div>
  )
}

function Post({ post, invitation }: { post: JobPost; invitation: MentorInvitation | undefined }) {
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
          <PhaseBadge phase={post.phase} />
          <ReportAbuseMenu
            targets={[
              { kind: 'JOB_POST', targetId: post.id, item: 'screen.report.post' },
              { kind: 'PRODUCT', targetId: post.productId, item: 'screen.report.product' },
            ]}
            label={t('screen.report.postMore')}
          />
        </div>
        <p className="text-body-sm font-semibold">{needText(post.need, post.recruitment?.openSlots)}</p>
        <p className="text-caption text-muted-foreground break-words">{meta.join(' · ')}</p>
      </div>
      {invitation?.status === 'OPEN' && (
        <div role="note" className="flex gap-3 rounded-md border-l-4 border-primary bg-primary-soft p-4 text-body-sm">
          <Info aria-hidden="true" className="size-5 shrink-0 text-primary" />
          <p className="break-words">{t('screen.jobPost.invitation.note')}</p>
        </div>
      )}
      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_300px]">
        <div className="flex min-w-0 flex-col gap-6">
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
          <ActionArea post={post} />
        </div>
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
        </aside>
      </div>
    </>
  )
}

/**
 * The one action area (UX-DR98): the Job's own people see their role; otherwise the bid while the Job looks for a
 * Mentor, or what the Mentor's recruitment seeks while it is on.
 */
function ActionArea({ post }: { post: JobPost }) {
  const role = useJobRole(post.id)
  if (role.isPending) {
    return <Skeleton className="h-40 w-full" />
  }
  if (role.data) {
    return <RoleNotice jobId={post.id} role={role.data} />
  }
  if (post.phase === 'FINDING_MENTOR') {
    return <BidPanel postId={post.id} />
  }
  if (post.recruitment) {
    return <CrewAction post={post} recruitment={post.recruitment} />
  }
  return (
    <p role="status" className="rounded-lg border border-border bg-card p-5 text-body-sm">
      {t('screen.jobPost.detail.notTaking')}
    </p>
  )
}

/**
 * "Xin vào nhóm" while the Mentor recruits (UX-DR98): the Mentor's name linked to their public profile, "Còn {n}
 * chỗ", the Skills sought and the Mentor's note. Story 21.4 adds the button and the Crew gates.
 */
function CrewAction({ post, recruitment }: { post: JobPost; recruitment: NonNullable<JobPost['recruitment']> }) {
  const mentor = post.mentor
  return (
    <section aria-labelledby="crew-heading" className="flex flex-col gap-3 rounded-lg border border-border bg-card p-5">
      <h2 id="crew-heading" className="text-h2">
        {t('screen.jobPost.detail.crewHeading')}
      </h2>
      {mentor && (
        <p className="text-body-sm break-words">
          {rich('screen.jobPost.detail.crewMentor', {
            name: mentor.githubLogin ? (
              <Link to={personPath(mentor.githubLogin)} className="text-primary underline-offset-4 hover:underline">
                {mentor.displayName}
              </Link>
            ) : (
              mentor.displayName
            ),
          })}
        </p>
      )}
      <p className="text-label font-semibold">{t('screen.jobPost.detail.slotsLeft', { n: recruitment.openSlots })}</p>
      {recruitment.skills.length > 0 && (
        <div className="flex flex-col gap-1.5">
          <p className="text-label font-semibold">{t('screen.jobPost.detail.crewSkills')}</p>
          <ul className="flex flex-wrap gap-1.5">
            {recruitment.skills.map((skill) => (
              <li key={skill} className="rounded-full bg-muted px-2 py-0.5 text-caption font-semibold">
                {skill}
              </li>
            ))}
          </ul>
        </div>
      )}
      {recruitment.note && (
        <div className="flex flex-col gap-1 text-body-sm">
          <p className="text-label font-semibold">{t('screen.jobPost.detail.crewNote')}</p>
          <p className="break-words whitespace-pre-line">{recruitment.note}</p>
        </div>
      )}
    </section>
  )
}

/**
 * The Customer's visible Rating summary (AD-33), with their kind and month joined ("Cá nhân · từ 07/2026");
 * a Job Post never names its Customer before selection (FR-12).
 */
function CustomerCard({ postId }: { postId: string }) {
  const rating = useCustomerRating(postId)
  return (
    <section aria-labelledby="post-customer" className="flex flex-col gap-1.5 rounded-lg border border-border bg-card p-4 text-body-sm">
      <h3 id="post-customer" className="text-h3">
        {t('screen.jobPost.detail.customer')}
      </h3>
      {rating.data?.customerJoinedAt && (
        <p>
          {rating.data.customerKind
            ? t('screen.jobPost.detail.customerSince', {
                kind: t(rating.data.customerKind === 'BUSINESS' ? 'screen.jobPost.detail.business' : 'screen.jobPost.detail.individual'),
                month: formatMonthYear(rating.data.customerJoinedAt),
              })
            : t('screen.jobPost.detail.customerJoined', { month: formatMonthYear(rating.data.customerJoinedAt) })}
        </p>
      )}
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
