import { Link, Navigate, useParams } from 'react-router'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { hasChat, useJobChat } from '@/features/chat/chatApi'
import { useSession } from '@/features/identity/useSession'
import { useJob, type Job } from '@/features/job/jobApi'
import { JobHeader } from '@/features/job/JobHeader'
import { customerMeta } from '@/features/job/jobMeta'
import { JobTabs } from '@/features/job/JobTabs'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t, type MessageKey } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatDayMonthTime } from '@/lib/dates'
import { formatVnd } from '@/lib/money'
import { isNotFound, problemMessage } from '@/lib/problemMessage'
import { redirectTo } from '@/lib/redirect'
import { cn } from '@/lib/utils'
import { useCreatePaymentOrder, useJobFunding, type FundingMilestone, type JobFunding } from './paymentApi'
import { RefundLine } from './RefundLine'
import { TestModeBanner, TestTag } from './TestModeBanner'

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
 * JOB-7 for the Customer (`/cong-viec/{jobId}/thanh-toan`, FR-41, FR-45, AD-14): N-13, each Milestone
 * with its state and "Thanh toán" for the next one (or why it waits), and the Payment Orders.
 * "Thanh toán" creates the order and goes to VNPay's sandbox page in the same tab; CUS-9 shows the
 * result. The Mentor's view by party comes with Story 4.8.
 */
export function JobPaymentPage() {
  const { jobId } = useParams()
  const account = useSession().data?.account
  if (!jobId || (account !== undefined && account.role !== 'CUSTOMER')) {
    return <NotFoundScreen />
  }
  return <CustomerPayment jobId={jobId} />
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
          <table className="w-full min-w-[620px] text-left text-body-sm">
            <thead className="text-caption text-muted-foreground">
              <tr className="border-b border-border">
                <th scope="col" className="px-4 py-2 font-semibold">
                  {t('screen.payment.milestone')}
                </th>
                <th scope="col" className="px-4 py-2 font-semibold">
                  {t('screen.payment.amount')}
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
                    <td className="px-4 py-3">
                      {formatVnd(milestone.priceVnd)} <TestTag />
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
