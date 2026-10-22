import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { TestTag } from '@/components/TestTag'
import { useSession } from '@/features/identity/useSession'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t } from '@/i18n/t'
import { formatDayMonth } from '@/lib/dates'
import { formatVnd } from '@/lib/money'
import { useBalance, type Balance } from './paymentApi'
import { TestModeBanner } from './TestModeBanner'

/**
 * MEN-7 and STU-7 (`/so-du`, FR-44, FR-45): the Balance, the sum of the account's releases, and each
 * release per Job. The Mentor also sees each Crew member's part; a Student sees only their own, even
 * after leaving the Crew (AD-20). The reader's own parts held for an open Team Issue show as "Đang giữ"
 * and never count in the Balance (AD-40). The Balance cannot be withdrawn.
 */
export function BalancePage() {
  usePageTitle(t('screen.payment.balance.title'))
  const account = useSession().data?.account
  const balance = useBalance()
  const mentor = account?.role === 'MENTOR'
  if (account !== undefined && account.role !== 'STUDENT' && !(mentor && account.mentorApprovalStatus === 'APPROVED')) {
    return <NotFoundScreen />
  }
  return (
    <div className="mx-auto flex max-w-[960px] flex-col gap-5 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <PageHeading>{t('screen.payment.balance.title')}</PageHeading>
      <TestModeBanner balance />
      <RegionState query={balance} skeleton={<Skeleton className="h-64 w-full" />}>
        {balance.data && <Content balance={balance.data} mentor={mentor} />}
      </RegionState>
    </div>
  )
}

function Content({ balance, mentor }: { balance: Balance; mentor: boolean }) {
  return (
    <>
      <div className="grid gap-3 md:grid-cols-3">
        <div className="flex flex-col gap-1 rounded-lg border border-border bg-card p-4">
          <span className="text-caption text-muted-foreground">{t('screen.payment.balance.yours')}</span>
          <p className="text-h3 tabular-nums">
            {formatVnd(balance.balanceVnd)} <TestTag />
          </p>
          <span className="text-caption text-muted-foreground">{t('screen.payment.balance.count', { n: balance.releases })}</span>
        </div>
        {mentor && balance.sentToCrewVnd != null && (
          <div className="flex flex-col gap-1 rounded-lg border border-border bg-card p-4">
            <span className="text-caption text-muted-foreground">{t('screen.payment.balance.sentToCrew')}</span>
            <p className="text-h3 tabular-nums">
              {formatVnd(balance.sentToCrewVnd)} <TestTag />
            </p>
          </div>
        )}
        {mentor && balance.heldVnd != null && (
          <div className="flex flex-col gap-1 rounded-lg border border-border bg-card p-4">
            <span className="text-caption text-muted-foreground">{t('screen.payment.balance.held')}</span>
            <p className="text-h3 tabular-nums">
              {formatVnd(balance.heldVnd)} <TestTag />
            </p>
          </div>
        )}
      </div>
      {balance.heldParts.length > 0 && (
        <section aria-labelledby="held-heading" className="flex flex-col gap-2 rounded-lg border border-border bg-card p-4">
          <h2 id="held-heading" className="text-h3">
            {t('screen.payment.balance.onHoldTitle')}
          </h2>
          <p className="text-body-sm text-muted-foreground">{t('screen.payment.balance.onHoldLead')}</p>
          <ul className="flex flex-col gap-1.5 text-body-sm">
            {balance.heldParts.map((part, index) => (
              <li key={`${part.jobId}-${part.number}-${index}`} className="flex flex-wrap justify-between gap-2">
                <span className="break-words">
                  {t('screen.payment.balance.onHoldLine', { job: part.jobTitle, n: part.number })}
                </span>
                <span className="tabular-nums">
                  {formatVnd(part.amountVnd)} <TestTag />
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}
      <section aria-labelledby="releases-heading" className="flex flex-col gap-2.5">
        <h2 id="releases-heading" className="text-h2">
          {t('screen.payment.balance.releasesTitle')}
        </h2>
        <p className="text-body-sm text-muted-foreground">
          {t(mentor ? 'screen.payment.balance.mentorLead' : 'screen.payment.balance.studentLead')}
        </p>
        {balance.lines.length === 0 ? (
          <p className="rounded-lg border border-border bg-card p-5 text-body-sm text-muted-foreground">
            {t('screen.payment.balance.empty')}
          </p>
        ) : (
          <div className="overflow-x-auto rounded-lg border border-border bg-card">
            <table className="w-full min-w-[640px] text-left text-body-sm">
              <thead className="text-caption text-muted-foreground">
                <tr className="border-b border-border">
                  <th scope="col" className="px-4 py-2 font-semibold">
                    {t('screen.payment.balance.job')}
                  </th>
                  <th scope="col" className="px-4 py-2 font-semibold">
                    {t('screen.payment.balance.date')}
                  </th>
                  <th scope="col" className="px-4 py-2 font-semibold">
                    {t('screen.payment.balance.milestone')}
                  </th>
                  <th scope="col" className="px-4 py-2 font-semibold">
                    {t('screen.payment.balance.yourPart')}
                  </th>
                  {mentor && (
                    <th scope="col" className="px-4 py-2 font-semibold">
                      {t('screen.payment.balance.crew')}
                    </th>
                  )}
                  <th scope="col" className="px-4 py-2 font-semibold">
                    {t('screen.payment.balance.terms')}
                  </th>
                </tr>
              </thead>
              <tbody>
                {balance.lines.map((line, index) => (
                  <tr key={`${line.jobId}-${line.number}-${index}`} className="border-b border-border align-top last:border-b-0">
                    <td className="px-4 py-2.5">
                      <p className="font-semibold break-words">{line.jobTitle}</p>
                      <p className="text-caption text-muted-foreground">
                        {t('screen.payment.balance.milestoneLine', { n: line.number, title: line.milestoneTitle })}
                      </p>
                    </td>
                    <td className="px-4 py-2.5">{formatDayMonth(line.releasedAt)}</td>
                    <td className="px-4 py-2.5 tabular-nums">
                      {formatVnd(line.baseAmountVnd)} <TestTag />
                    </td>
                    <td className="px-4 py-2.5 tabular-nums">
                      {formatVnd(line.yourPartVnd)} <TestTag />
                      {line.yourPercent != null && (
                        <p className="text-caption text-muted-foreground">{t('screen.payment.balance.percent', { n: line.yourPercent })}</p>
                      )}
                    </td>
                    {mentor && (
                      <td className="px-4 py-2.5">
                        {line.crew.map((part) => (
                          <p key={`${part.name}-${part.amountVnd}`} className="tabular-nums">
                            {part.name} {formatVnd(part.amountVnd)} <TestTag />
                            {part.percent != null && (
                              <span className="text-caption text-muted-foreground"> · {t('screen.payment.balance.percent', { n: part.percent })}</span>
                            )}
                          </p>
                        ))}
                      </td>
                    )}
                    <td className="px-4 py-2.5">
                      {line.crewTermsVersionNo != null && t('screen.payment.balance.version', { n: line.crewTermsVersionNo })}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {mentor && <p className="text-caption text-muted-foreground">{t('screen.payment.balance.customerNote')}</p>}
      </section>
    </>
  )
}
