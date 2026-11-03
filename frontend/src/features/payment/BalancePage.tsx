import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { TestTag } from '@/components/TestTag'
import { t } from '@/i18n/t'
import { formatVnd } from '@/lib/money'
import { useBalance, type Balance } from './paymentApi'
import { PartLines } from './PartLines'
import { hasMentorLine } from './partText'
import { TestModeBanner } from './TestModeBanner'

/**
 * MEN-7, which STU-7 merged into (`/so-du`, FR-44, FR-45, AD-15, Story 22.5): N-13's banner, the Balance, the sum of
 * the account's own RELEASE entries, and one line per Job and Milestone with the role held there. A split the user is
 * a party of and not yet paid out shows "Chờ phân chia" with its pooled amount, which counts in no Balance; a former
 * member reads theirs too. Each line decides on its own whether it lists the Crew's parts: only where its split names
 * the user its Mentor (AD-20). The Balance cannot be withdrawn.
 */
export function BalancePage() {
  usePageTitle(t('screen.payment.balance.title'))
  const balance = useBalance()
  return (
    <div className="mx-auto flex max-w-[960px] flex-col gap-5 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <PageHeading>{t('screen.payment.balance.title')}</PageHeading>
      <TestModeBanner balance />
      <RegionState query={balance} skeleton={<Skeleton className="h-64 w-full" />}>
        {balance.data && <Content balance={balance.data} />}
      </RegionState>
    </div>
  )
}

function Content({ balance }: { balance: Balance }) {
  // The lead and the note follow the Mentor's figures too, for a Mentor with no line yet; each line decides its parts.
  const mentor = hasMentorLine(balance.lines) || balance.sentToCrewVnd != null
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
        {balance.sentToCrewVnd != null && (
          <div className="flex flex-col gap-1 rounded-lg border border-border bg-card p-4">
            <span className="text-caption text-muted-foreground">{t('screen.payment.balance.sentToCrew')}</span>
            <p className="text-h3 tabular-nums">
              {formatVnd(balance.sentToCrewVnd)} <TestTag />
            </p>
          </div>
        )}
        {balance.heldVnd != null && (
          <div className="flex flex-col gap-1 rounded-lg border border-border bg-card p-4">
            <span className="text-caption text-muted-foreground">{t('screen.payment.balance.held')}</span>
            <p className="text-h3 tabular-nums">
              {formatVnd(balance.heldVnd)} <TestTag />
            </p>
          </div>
        )}
      </div>
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
          <PartLines lines={balance.lines} crew />
        )}
        {mentor && <p className="text-caption text-muted-foreground">{t('screen.payment.balance.customerNote')}</p>}
      </section>
    </>
  )
}
