import { Link, Navigate, useParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { hasChat, useJobChat } from '@/features/chat/chatApi'
import { useCrewMe } from '@/features/crew/crewApi'
import { ByJobRole, JobRoleLine } from '@/features/job/ByJobRole'
import { useJob, type Job } from '@/features/job/jobApi'
import { JobHeader } from '@/features/job/JobHeader'
import { customerMeta } from '@/features/job/jobMeta'
import { JobTabs } from '@/features/job/JobTabs'
import { mentorMeta } from '@/features/job/jobMeta'
import { MentorJobTabs } from '@/features/matching/MentorJobTabs'
import { useMentorJob } from '@/features/matching/mentorJobApi'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t, type MessageKey } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatDayMonthTime } from '@/lib/dates'
import { formatVnd } from '@/lib/money'
import { isNotFound, problemMessage } from '@/lib/problemMessage'
import { redirectTo } from '@/lib/redirect'
import { cn } from '@/lib/utils'
import {
  useBalance,
  useCreatePaymentOrder,
  useJobFunding,
  useMentorJobFunding,
  type FundingMilestone,
  type JobFunding,
  type MentorFundingMilestone,
  type MentorJobFunding,
} from './paymentApi'
import { PartLines } from './PartLines'
import { finalText, partyName, pendingText, pendingTone } from './partText'
import { RefundLine } from './RefundLine'
import { TestTag } from '@/components/TestTag'
import { TestModeBanner } from './TestModeBanner'
import { hasBoard } from '@/features/work/workApi'

const STATE_TONE: Record<string, string> = {
  PLANNED: 'bg-warning-soft text-warning',
  LATER: 'bg-muted text-muted-foreground',
  FUNDED: 'bg-primary-soft text-primary',
  ACCEPTED: 'bg-success-soft text-success',
  CANCELLED: 'bg-muted text-muted-foreground',
}

const ORDER_TONE: Record<string, string> = {
  OPEN: 'bg-primary-soft text-primary',
  PAID: 'bg-success-soft text-success',
  UNAPPLIED: 'bg-warning-soft text-warning',
}

/**
 * JOB-7 (`/cong-viec/{jobId}/thanh-toan`, FR-41, FR-44, FR-45, AD-14, AD-20), one variant per role in the Job. The
 * Customer's: N-13, each Milestone with its state and "Thanh toán" for the next one (or why it waits), and the
 * Payment Orders; "Thanh toán" creates the order and goes to VNPay's sandbox page in the same tab, and CUS-9 shows the
 * result. It never shows a part, a party, a pool, or a split. The Mentor's reads its own Assignee DTO: each
 * Milestone's split pending as "Chờ phân chia" with its pool, then each party's part. A Crew member's shows their own
 * lines of this Job from MEN-7 (Story 22.5, UX-DR106), and nothing for a member of no split yet.
 */
export function JobPaymentPage() {
  const { jobId } = useParams()
  return (
    <ByJobRole
      jobId={jobId}
      customer={() => <CustomerPayment jobId={jobId ?? ''} />}
      mentor={() => <MentorPayment jobId={jobId ?? ''} />}
      crew={() => <CrewPayment jobId={jobId ?? ''} />}
    />
  )
}

/**
 * A Crew member's JOB-7: their own parts of this Job and the status of the splits they are a party of, read from
 * MEN-7's lines, so one access rule covers both and nobody else's line can show. A member before their signature finds
 * nothing, as on JOB-1.
 */
function CrewPayment({ jobId }: { jobId: string }) {
  const me = useCrewMe(jobId)
  const balance = useBalance()
  usePageTitle(me.data ? t('screen.payment.title', { job: me.data.title }) : t('screen.job.title'))
  if (me.isError && isNotFound(me.error)) {
    return <NotFoundScreen />
  }
  const lines = balance.data?.lines.filter((line) => line.jobId === jobId) ?? []
  return (
    <div className="mx-auto flex max-w-[1120px] flex-col gap-5 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <div className="flex flex-col gap-3">
        <Link
          to={`/cong-viec/${jobId}`}
          className="w-fit text-label text-muted-foreground underline-offset-4 hover:underline"
        >
          {t('screen.split.back')}
        </Link>
        <RegionState query={me} skeleton={<Skeleton className="h-10 w-full" />}>
          {me.data && <PageHeading>{t('screen.payment.title', { job: me.data.title })}</PageHeading>}
        </RegionState>
        <JobRoleLine jobId={jobId} />
      </div>
      <TestModeBanner />
      <section aria-labelledby="crew-parts-heading" className="flex flex-col gap-2.5">
        <h2 id="crew-parts-heading" className="text-h2">
          {t('screen.payment.history.partsTitle')}
        </h2>
        <p className="text-body-sm text-muted-foreground">{t('screen.payment.crew.lead')}</p>
        <RegionState query={balance} skeleton={<Skeleton className="h-32 w-full" />}>
          {balance.data &&
            (lines.length === 0 ? (
              <p className="rounded-lg border border-border bg-card p-5 text-body-sm text-muted-foreground">
                {t('screen.payment.crew.empty')}
              </p>
            ) : (
              <PartLines lines={lines} crew={false} job={false} />
            ))}
        </RegionState>
      </section>
    </div>
  )
}

function MentorPayment({ jobId }: { jobId: string }) {
  const job = useMentorJob(jobId)
  const funding = useMentorJobFunding(jobId)
  usePageTitle(job.data ? t('screen.payment.title', { job: job.data.title }) : t('screen.job.title'))
  if ((job.isError && isNotFound(job.error)) || (funding.isError && isNotFound(funding.error))) {
    return <NotFoundScreen />
  }
  return (
    <div className="mx-auto flex max-w-[1120px] flex-col gap-5 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <RegionState query={job} skeleton={<Skeleton className="h-24 w-full" />}>
        {job.data && (
          <JobHeader title={job.data.title} state={job.data.state} meta={mentorMeta(job.data)}>
            <MentorJobTabs jobId={job.data.id} state={job.data.state} current="payment" payment={job.data.agreementConfirmed} />
          </JobHeader>
        )}
      </RegionState>
      <TestModeBanner />
      <RegionState query={funding} skeleton={<Skeleton className="h-64 w-full" />}>
        {funding.data && <MentorFunding funding={funding.data} />}
      </RegionState>
    </div>
  )
}

function MentorFunding({ funding }: { funding: MentorJobFunding }) {
  return (
    <>
      <section aria-labelledby="mentor-funding-heading" className="flex flex-col gap-3">
        <h2 id="mentor-funding-heading" className="text-h2">
          {t('screen.payment.milestonesTitle')}
        </h2>
        {funding.milestones.map((milestone) => (
          <MentorMilestone key={milestone.milestoneId} jobId={funding.jobId} milestone={milestone} />
        ))}
      </section>
      <section aria-labelledby="mentor-orders-heading" className="flex flex-col gap-2 rounded-lg border border-border bg-card p-5">
        <h2 id="mentor-orders-heading" className="text-h3">
          {t('screen.payment.mentor.ordersTitle')}
        </h2>
        {funding.orders.length === 0 ? (
          <p className="text-body-sm text-muted-foreground">{t('screen.payment.noOrders')}</p>
        ) : (
          <ul className="flex flex-col divide-y divide-border text-body-sm">
            {funding.orders.map((order, index) => (
              <li key={`${order.createdAt}-${index}`} className="flex flex-wrap items-center justify-between gap-2 py-2">
                <span className="tabular-nums">
                  {t('screen.payment.history.funding', { n: order.number })} · {formatVnd(order.amountVnd)} <TestTag />
                </span>
                <span className={cn('rounded-full px-2 py-0.5 text-caption font-semibold', ORDER_TONE[order.status] ?? 'bg-muted')}>
                  {t(`screen.payment.orderStatus.${order.status}` as MessageKey)}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  )
}

/**
 * One Milestone of the Mentor's JOB-7. While its split has not paid out, "Đã chuyển" hides, since it counts the pool,
 * and the pooled amount shows as "Chờ phân chia" with the way to JOB-10 (UX-DR106); then each party's final part, an
 * erased person's as JOB-10 names them.
 */
function MentorMilestone({ jobId, milestone }: { jobId: string; milestone: MentorFundingMilestone }) {
  const pending = milestone.pooledVnd != null && !milestone.paid
  const final =
    milestone.splitStatus === 'FINAL' && !pending ? finalText(milestone.finality, milestone.finalAt) : undefined
  return (
    <article className="flex flex-col gap-2 rounded-lg border border-border bg-card p-5 text-body-sm">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-h3 break-words">{t('screen.payment.row', { n: milestone.number, title: milestone.title })}</h3>
        <span className={cn('rounded-full px-2 py-0.5 text-caption font-semibold', STATE_TONE[milestone.state] ?? 'bg-muted')}>
          {t(`screen.payment.milestoneState.${milestone.state}` as MessageKey)}
        </span>
      </div>
      <p className="flex flex-wrap gap-x-5 gap-y-1 tabular-nums">
        <span>
          {t('screen.payment.amount')}: {formatVnd(milestone.priceVnd)} <TestTag />
        </span>
        <span>
          {t('screen.payment.funded')}: {formatVnd(milestone.fundedVnd + milestone.topUpVnd)} <TestTag />
        </span>
        {!pending && (
          <span>
            {t('screen.payment.released')}: {formatVnd(milestone.releasedTotalVnd)} <TestTag />
          </span>
        )}
      </p>
      <p className="text-label font-semibold">{t('screen.payment.mentor.releasesTitle')}</p>
      {pending ? (
        <div className="flex flex-col items-start gap-1">
          <span
            className={cn('rounded-full px-2 py-0.5 text-caption font-semibold', pendingTone(milestone.splitStatus))}
          >
            {pendingText(milestone.splitStatus)}
          </span>
          <p className="tabular-nums">
            {formatVnd(milestone.pooledVnd ?? 0)} <TestTag />{' '}
            {t('screen.split.ofMilestonePending', { n: milestone.number })}
          </p>
          <Link
            to={`/cong-viec/${jobId}/phan-chia/${milestone.milestoneId}`}
            className="text-primary underline-offset-4 hover:underline"
          >
            {t('screen.payment.balance.openSplit')}
          </Link>
        </div>
      ) : milestone.releases.length === 0 ? (
        <p className="text-muted-foreground">{t('screen.payment.mentor.noReleases')}</p>
      ) : (
        <ul className="flex flex-col gap-1">
          {milestone.releases.map((release, index) => (
            <li key={`${release.name}-${index}`} className="flex flex-wrap items-center gap-2 tabular-nums">
              <span className="font-semibold break-words">{partyName(release)}</span>
              <span className="text-caption text-muted-foreground">{t(`screen.payment.mentor.role.${release.role}` as MessageKey)}</span>
              <span>
                {formatVnd(release.amountVnd)} <TestTag />
              </span>
              {release.percent != null && <span>{t('screen.payment.balance.percent', { n: release.percent })}</span>}
              {release.splitVersionNo != null && (
                <span className="text-caption text-muted-foreground">
                  {t('screen.payment.balance.version', { n: release.splitVersionNo })}
                </span>
              )}
            </li>
          ))}
        </ul>
      )}
      {final && <p className="text-caption text-muted-foreground">{final}</p>}
    </article>
  )
}

function CustomerPayment({ jobId }: { jobId: string }) {
  const job = useJob(jobId)
  const funding = useJobFunding(jobId)
  usePageTitle(job.data ? t('screen.payment.title', { job: job.data.title }) : t('screen.job.title'))
  if (job.isError && isNotFound(job.error)) {
    return <NotFoundScreen />
  }
  if (job.data && !job.data.agreementConfirmed) {
    return <Navigate to={`/cong-viec/${job.data.id}`} replace />
  }
  return (
    <div className="mx-auto flex max-w-[1120px] flex-col gap-5 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <RegionState query={job} skeleton={<Skeleton className="h-24 w-full" />}>
        {job.data && <Header job={job.data} />}
      </RegionState>
      <TestModeBanner />
      <RegionState query={funding} skeleton={<Skeleton className="h-64 w-full" />}>
        {funding.data && <Funding funding={funding.data} />}
      </RegionState>
    </div>
  )
}

function Header({ job }: { job: Job }) {
  const chat = useJobChat(hasChat(job.state) ? job.id : undefined)
  return (
    <JobHeader title={job.title} state={job.state} meta={customerMeta(job)}>
      <JobTabs
        jobId={job.id}
        current="payment"
        chat={hasChat(job.state) ? { unread: chat.data?.unread ?? false } : undefined}
        agreement={job.agreementShared}
        payment={job.agreementConfirmed}
        docs={hasBoard(job.state)}
        handover={hasBoard(job.state)}
      />
    </JobHeader>
  )
}

function stateKey(milestone: FundingMilestone, next: string | undefined): string {
  return milestone.state === 'PLANNED' && milestone.milestoneId !== next ? 'LATER' : milestone.state
}

function blockedText(funding: JobFunding, milestone: FundingMilestone): string | undefined {
  if (!funding.configured) {
    return t('screen.payment.notConfigured')
  }
  if (milestone.fundable || !milestone.blockedReason) {
    return undefined
  }
  if (milestone.blockedReason === 'JOB_FROZEN') {
    return t('screen.payment.frozen')
  }
  if (milestone.blockedReason === 'NO_SIGNED_CREW') {
    // R7, Story 21.5: the Customer's words name no one.
    return t('screen.payment.noSignedCrew')
  }
  const reason = t(`reason.job.${milestone.blockedReason}` as MessageKey)
  return reason.startsWith('reason.') ? undefined : reason.charAt(0).toUpperCase() + reason.slice(1)
}

function Funding({ funding }: { funding: JobFunding }) {
  const create = useCreatePaymentOrder(funding.jobId)
  const toasts = useToasts()
  const next = funding.milestones.find((milestone) => milestone.state === 'PLANNED')?.milestoneId
  const pay = (milestone: FundingMilestone) =>
    create.mutate(
      { milestoneId: milestone.milestoneId, version: funding.version },
      {
        onSuccess: (created) => redirectTo(created.payUrl),
        onError: (error) =>
          toasts.error(
            error instanceof ApiError && error.problem.code === 'STALE'
              ? t('error.STALE')
              : problemMessage(error, { module: error instanceof ApiError && error.problem.reason === 'PAYMENT_NOT_CONFIGURED' ? 'payment' : 'job', action: t('screen.payment.payAction') }).text,
          ),
      },
    )
  return (
    <>
      <section aria-labelledby="funding-heading" className="flex flex-col gap-2.5">
        <h2 id="funding-heading" className="text-h2">
          {t('screen.payment.milestonesTitle')}
        </h2>
        <div className="overflow-x-auto rounded-lg border border-border bg-card">
          <table className="w-full min-w-[760px] text-left text-body-sm">
            <thead className="text-caption text-muted-foreground">
              <tr className="border-b border-border">
                <th scope="col" className="px-4 py-2 font-semibold">
                  {t('screen.payment.milestone')}
                </th>
                <th scope="col" className="px-4 py-2 font-semibold">
                  {t('screen.payment.amount')}
                </th>
                <th scope="col" className="px-4 py-2 font-semibold">
                  {t('screen.payment.funded')}
                </th>
                <th scope="col" className="px-4 py-2 font-semibold">
                  {t('screen.payment.released')}
                </th>
                <th scope="col" className="px-4 py-2 font-semibold">
                  {t('screen.payment.status')}
                </th>
                <th scope="col" className="px-4 py-2">
                  <span className="sr-only">{t('screen.payment.pay')}</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {funding.milestones.map((milestone) => {
                const key = stateKey(milestone, next)
                const blocked = milestone.milestoneId === next ? blockedText(funding, milestone) : undefined
                return (
                  <tr key={milestone.milestoneId} className="border-b border-border align-top last:border-b-0">
                    <td className="px-4 py-3 break-words">{t('screen.payment.row', { n: milestone.number, title: milestone.title })}</td>
                    <td className="px-4 py-3 tabular-nums">
                      {formatVnd(milestone.priceVnd)} <TestTag />
                    </td>
                    <td className="px-4 py-3 tabular-nums">
                      {formatVnd(milestone.fundedVnd + milestone.topUpVnd)} <TestTag />
                    </td>
                    <td className="px-4 py-3 tabular-nums">
                      {formatVnd(milestone.releasedTotalVnd)} <TestTag />
                      {milestone.refundAtEndVnd > 0 && (
                        <p className="text-caption text-muted-foreground">
                          {t('screen.payment.refundAtEnd', { amount: formatVnd(milestone.refundAtEndVnd) })}
                        </p>
                      )}
                    </td>
                    <td className="px-4 py-3">
                      <span className={cn('rounded-full px-2 py-0.5 text-caption font-semibold', STATE_TONE[key] ?? 'bg-muted')}>
                        {t(`screen.payment.milestoneState.${key}` as MessageKey)}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      {milestone.milestoneId === next && (
                        <div className="flex flex-col items-start gap-1">
                          <Button
                            type="button"
                            size="compact"
                            aria-label={t('screen.payment.payLabel', { n: milestone.number })}
                            aria-describedby={blocked ? `blocked-${milestone.milestoneId}` : undefined}
                            disabled={!milestone.fundable || !funding.configured || create.isPending}
                            onClick={() => pay(milestone)}
                          >
                            {create.isPending ? t('screen.payment.paying') : t('screen.payment.pay')}
                          </Button>
                          {blocked && (
                            <p id={`blocked-${milestone.milestoneId}`} className="text-caption text-muted-foreground">
                              {blocked}
                            </p>
                          )}
                        </div>
                      )}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      </section>
      <section aria-labelledby="orders-heading" className="flex flex-col gap-2 rounded-lg border border-border bg-card p-5">
        <h2 id="orders-heading" className="text-h3">
          {t('screen.payment.ordersTitle')}
        </h2>
        {funding.orders.length === 0 ? (
          <p className="text-body-sm text-muted-foreground">{t('screen.payment.noOrders')}</p>
        ) : (
          <ul className="flex flex-col divide-y divide-border">
            {funding.orders.map((order) => (
              <li key={order.id} className="flex flex-wrap items-center justify-between gap-2 py-2 text-body-sm">
                <span className="flex flex-col gap-0.5">
                  <span>
                    {formatVnd(order.amountVnd)} <TestTag />
                  </span>
                  {order.refund && <RefundLine refund={order.refund} />}
                  <span className="text-caption text-muted-foreground">
                    {t('screen.payment.orderLine', {
                      n: order.number,
                      time: formatDayMonthTime(order.createdAt),
                      reference: order.reference.slice(0, 8),
                    })}
                  </span>
                </span>
                <span className="flex items-center gap-3">
                  <span className={cn('rounded-full px-2 py-0.5 text-caption font-semibold', ORDER_TONE[order.status] ?? 'bg-muted')}>
                    {t(`screen.payment.orderStatus.${order.status}` as MessageKey)}
                  </span>
                  <Link to={`/thanh-toan/${order.id}`} className="text-primary underline-offset-4 hover:underline">
                    {t('screen.payment.orderOpen')}
                  </Link>
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  )
}
