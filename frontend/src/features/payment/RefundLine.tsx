import { Undo2 } from 'lucide-react'
import type { components } from '@/api/schema'
import { t, type MessageKey } from '@/i18n/t'
import { formatVnd } from '@/lib/money'
import { TestTag } from '@/components/TestTag'

type Refund = components['schemas']['PaymentRefundView']

/**
 * A refund (FR-43, AD-15): "Đang hoàn" or "Đã hoàn". A VNPay refund reads as a normal refund; only one
 * recorded on vibefix by the fallback gateway is labelled "Hoàn tiền mô phỏng", with its note. With
 * `reason`, why it was refunded.
 */
export function RefundLine({ refund, reason = false }: { refund: Refund; reason?: boolean }) {
  const amount = formatVnd(refund.amountVnd)
  return (
    <div className="flex flex-col gap-1 text-body-sm">
      <p className="flex flex-wrap items-center gap-1.5">
        <Undo2 aria-hidden="true" className="size-4 text-primary" />
        {refund.simulated && <span className="font-semibold">{t('screen.payment.refund.simulated')} ·</span>}
        <span>
          {t(refund.status === 'CONFIRMED' ? 'screen.payment.refund.confirmed' : 'screen.payment.refund.requested', { amount })}
        </span>
        <TestTag />
      </p>
      {reason && <p>{t(`screen.payment.refund.reason.${refund.reason}` as MessageKey)}</p>}
      {reason && refund.simulated && <p className="text-caption text-muted-foreground">{t('screen.payment.refund.simulatedNote')}</p>}
    </div>
  )
}
