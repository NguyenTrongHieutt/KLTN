import { Link } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { TestTag } from '@/components/TestTag'
import { t, type MessageKey } from '@/i18n/t'
import { formatDayMonthTime } from '@/lib/dates'
import { formatVnd } from '@/lib/money'
import { useBalance, usePaymentHistory, type BalanceLine, type CustomerHistory } from './paymentApi'
import { PartLines } from './PartLines'
import { TestModeBanner } from './TestModeBanner'

/**
 * "Lịch sử thanh toán" (`/lich-su-thanh-toan`, FR-44, FR-45, UX-DR85, Story 22.5), in two sections from two separate
 * DTOs (AD-20): CUS-10's, every funding and refund per Job the user posted with per Milestone the released total, never
 * what each person received; and "Phần của bạn", MEN-7's own lines, each with its Job and the role held there. Each
 * section hides when it holds nothing.
 */
export function PaymentHistoryPage() {
  usePageTitle(t('screen.payment.history.title'))
  const history = usePaymentHistory()
  const balance = useBalance()
  const empty = history.data?.jobs.length === 0 && balance.data?.lines.length === 0
  return (
    <div className="mx-auto flex max-w-[1120px] flex-col gap-5 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <div className="flex flex-col gap-0.5">
        <PageHeading>{t('screen.payment.history.title')}</PageHeading>
        <p className="text-body-sm text-muted-foreground">{t('screen.payment.history.lead')}</p>
      </div>
      <TestModeBanner />
      {empty && <p className="text-body-sm text-muted-foreground">{t('screen.payment.history.empty')}</p>}
      <RegionState query={history} skeleton={<Skeleton className="h-64 w-full" />}>
        {history.data && history.data.jobs.length > 0 && <History history={history.data} />}
      </RegionState>
      <RegionState query={balance} skeleton={<Skeleton className="h-32 w-full" />}>
        {balance.data && balance.data.lines.length > 0 && <Parts lines={balance.data.lines} />}
      </RegionState>
    </div>
  )
}

function Stat({ label, amount }: { label: MessageKey; amount: number }) {
  return (
    <div className="flex flex-col gap-1 rounded-lg border border-border bg-card p-4">
      <span className="text-caption text-muted-foreground">{t(label)}</span>
      <p className="text-h3 tabular-nums">
        {formatVnd(amount)} <TestTag />
      </p>
    </div>
  )
}

function rowItem(row: CustomerHistory['jobs'][number]['rows'][number]): string {
  if (row.kind === 'REFUND') {
    return t(row.simulated ? 'screen.payment.history.refundSimulated' : 'screen.payment.history.refund', { n: row.number })
  }
  return t('screen.payment.history.funding', { n: row.number })
}

function rowStatus(row: CustomerHistory['jobs'][number]['rows'][number]): string {
  return row.kind === 'REFUND'
    ? t(`screen.payment.history.refundStatus.${row.status}` as MessageKey)
    : t(`screen.payment.orderStatus.${row.status}` as MessageKey)
}

/** The user's fundings and refunds as the Customer of each Job (CUS-10). */
function History({ history }: { history: CustomerHistory }) {
  return (
    <section aria-labelledby="history-customer" className="flex flex-col gap-4">
      <h2 id="history-customer" className="text-h2">
        {t('screen.payment.history.customerTitle')}
      </h2>
      <div className="grid gap-3 md:grid-cols-3">
        <Stat label="screen.payment.history.held" amount={history.heldVnd} />
        <Stat label="screen.payment.history.released" amount={history.releasedVnd} />
        <Stat label="screen.payment.history.refunded" amount={history.refundedVnd} />
      </div>
      {history.jobs.map((job) => (
        <section key={job.jobId} aria-labelledby={`history-${job.jobId}`} className="flex flex-col gap-2.5">
          <h3 id={`history-${job.jobId}`} className="text-h3 break-words">
            <Link to={`/cong-viec/${job.jobId}/thanh-toan`} className="text-foreground underline-offset-4 hover:underline">
              {job.title}
            </Link>
          </h3>
          <div className="overflow-x-auto rounded-lg border border-border bg-card">
            <table className="w-full min-w-[560px] text-left text-body-sm">
              <thead className="text-caption text-muted-foreground">
                <tr className="border-b border-border">
                  <th scope="col" className="px-4 py-2 font-semibold">
                    {t('screen.payment.history.time')}
                  </th>
                  <th scope="col" className="px-4 py-2 font-semibold">
                    {t('screen.payment.history.item')}
                  </th>
                  <th scope="col" className="px-4 py-2 font-semibold">
                    {t('screen.payment.history.amount')}
                  </th>
                  <th scope="col" className="px-4 py-2 font-semibold">
                    {t('screen.payment.history.status')}
                  </th>
                </tr>
              </thead>
              <tbody>
                {job.rows.map((row, index) => (
                  <tr key={`${row.paymentOrderId}-${row.kind}-${index}`} className="border-b border-border last:border-b-0">
                    <td className="px-4 py-2.5">{formatDayMonthTime(row.at)}</td>
                    <td className="px-4 py-2.5">{rowItem(row)}</td>
                    <td className="px-4 py-2.5 tabular-nums">
                      {formatVnd(row.amountVnd)} <TestTag />
                    </td>
                    <td className="px-4 py-2.5">{rowStatus(row)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <ul className="flex flex-col gap-0.5 text-caption text-muted-foreground">
            {job.milestones
              .filter((milestone) => milestone.releasedTotalVnd > 0)
              .map((milestone) => (
                <li key={milestone.milestoneId}>
                  {t('screen.payment.history.releasedLine', {
                    n: milestone.number,
                    title: milestone.title,
                    amount: formatVnd(milestone.releasedTotalVnd),
                  })}
                </li>
              ))}
          </ul>
        </section>
      ))}
    </section>
  )
}

/** "Phần của bạn": the user's parts as a Mentor or a Crew member, each with its Job and role (FR-44, UX-DR85). */
function Parts({ lines }: { lines: BalanceLine[] }) {
  return (
    <section aria-labelledby="history-parts" className="flex flex-col gap-2.5">
      <h2 id="history-parts" className="text-h2">
        {t('screen.payment.history.partsTitle')}
      </h2>
      <p className="text-body-sm text-muted-foreground">{t('screen.payment.history.partsLead')}</p>
      <PartLines lines={lines} crew={false} />
    </section>
  )
}
