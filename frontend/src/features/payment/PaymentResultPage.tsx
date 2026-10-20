import { CircleCheck, CircleX, LoaderCircle, TriangleAlert } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { buttonVariants } from '@/components/ui/button-variants'
import { useSession } from '@/features/identity/useSession'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t, type MessageKey } from '@/i18n/t'
import { formatDayMonthTime, formatTime } from '@/lib/dates'
import { formatVnd } from '@/lib/money'
import { isNotFound } from '@/lib/problemMessage'
import { FAST_POLL_FOR_MS, usePaymentOrder, type PaymentOrder } from './paymentApi'
import { RefundLine } from './RefundLine'
import { TestTag } from '@/components/TestTag'
import { TestModeBanner } from './TestModeBanner'

/**
 * CUS-9 (`/thanh-toan/{paymentOrderId}`, AD-14, UX-DR25): VNPay's return URL. It only displays the
 * order's status read from the server, polling every 2 s for 2 minutes and then every 15 s, until the
 * order is final; nothing here changes state.
 */
export function PaymentResultPage() {
  const { paymentOrderId } = useParams()
  const account = useSession().data?.account
  usePageTitle(t('screen.payment.result.title'))
  if (!paymentOrderId || (account !== undefined && account.role !== 'CUSTOMER')) {
    return <NotFoundScreen />
  }
  return <Result paymentOrderId={paymentOrderId} />
}

function Result({ paymentOrderId }: { paymentOrderId: string }) {
  const [startedAt] = useState(() => Date.now())
  const [slow, setSlow] = useState(false)
  const order = usePaymentOrder(paymentOrderId, startedAt)
  useEffect(() => {
    const timer = window.setTimeout(() => setSlow(true), FAST_POLL_FOR_MS)
    return () => window.clearTimeout(timer)
  }, [])
  if (order.isError && isNotFound(order.error)) {
    return <NotFoundScreen />
  }
  return (
    <div className="mx-auto flex w-full max-w-[600px] flex-col gap-5 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <TestModeBanner />
      <RegionState query={order} skeleton={<Skeleton className="h-64 w-full" />}>
        {order.data && <Outcome order={order.data} slow={slow} />}
      </RegionState>
    </div>
  )
}

const OUTCOME: Record<string, { title: MessageKey; body: MessageKey; tone: 'ok' | 'bad' | 'warn' }> = {
  PAID: { title: 'screen.payment.result.paid', body: 'screen.payment.result.paidBody', tone: 'ok' },
  FAILED: { title: 'screen.payment.result.failed', body: 'screen.payment.result.failedBody', tone: 'bad' },
  EXPIRED: { title: 'screen.payment.result.expired', body: 'screen.payment.result.expiredBody', tone: 'bad' },
  VOIDED: { title: 'screen.payment.result.voided', body: 'screen.payment.result.voidedBody', tone: 'warn' },
  UNAPPLIED: { title: 'screen.payment.result.unapplied', body: 'screen.payment.result.unappliedBody', tone: 'warn' },
}

function Outcome({ order, slow }: { order: PaymentOrder; slow: boolean }) {
  const outcome = OUTCOME[order.status]
  return (
    <>
      <section aria-live="polite" className="flex flex-col items-center gap-3 rounded-lg border border-border bg-card px-6 py-8 text-center">
        {outcome === undefined ? (
          <LoaderCircle aria-hidden="true" className="size-12 animate-spin text-primary" />
        ) : outcome.tone === 'ok' ? (
          <CircleCheck aria-hidden="true" className="size-12 text-success" />
        ) : outcome.tone === 'bad' ? (
          <CircleX aria-hidden="true" className="size-12 text-destructive" />
        ) : (
          <TriangleAlert aria-hidden="true" className="size-12 text-warning" />
        )}
        <h1 className="text-h1">{t(outcome?.title ?? 'screen.payment.result.pending')}</h1>
        <p className="text-h3">
          {formatVnd(order.amountVnd)} <TestTag />
        </p>
        <p className="text-body-sm text-muted-foreground">
          {outcome === undefined
            ? slow
              ? t('screen.payment.result.slow', { time: formatTime(order.expiresAt) })
              : t('screen.payment.result.pendingBody')
            : t(outcome.body, { n: order.number })}
        </p>
        {order.refund && (
          <div className="w-full rounded-md bg-muted p-3 text-left">
            <RefundLine refund={order.refund} reason />
          </div>
        )}
        <dl className="mt-2 grid w-full grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 border-t border-border pt-4 text-left text-body-sm">
          <dt className="text-muted-foreground">{t('screen.payment.result.job')}</dt>
          <dd className="break-words">{order.jobTitle}</dd>
          <dt className="text-muted-foreground">{t('screen.payment.result.item')}</dt>
          <dd>{t('screen.payment.result.itemValue', { n: order.number })}</dd>
          <dt className="text-muted-foreground">{t('screen.payment.result.reference')}</dt>
          <dd className="font-mono break-all">{order.reference}</dd>
          <dt className="text-muted-foreground">{t('screen.payment.result.time')}</dt>
          <dd>{formatDayMonthTime(order.createdAt)}</dd>
        </dl>
      </section>
      <Link to={`/cong-viec/${order.jobId}/thanh-toan`} className={buttonVariants({ variant: 'secondary', className: 'self-center' })}>
        {t('screen.payment.result.backToJob')}
      </Link>
    </>
  )
}
