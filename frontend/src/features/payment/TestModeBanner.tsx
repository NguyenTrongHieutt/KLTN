import { FlaskConical } from 'lucide-react'
import { useId, useState } from 'react'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { useTestCard } from './paymentApi'

export function TestTag() {
  return <span className="rounded bg-warning-soft px-1 text-caption font-semibold text-warning">{t('screen.matching.test')}</span>
}

/**
 * N-13 (FR-45): not dismissible, on every payment surface; "Cách thanh toán thử" opens the test card
 * VNPay publishes for its sandbox, from configuration.
 */
export function TestModeBanner() {
  const ids = useId()
  const [open, setOpen] = useState(false)
  const card = useTestCard(open)
  const rows = card.data?.configured
    ? ([
        ['screen.payment.helpBank', card.data.bank],
        ['screen.payment.helpNumber', card.data.number],
        ['screen.payment.helpHolder', card.data.holder],
        ['screen.payment.helpIssued', card.data.issued],
        ['screen.payment.helpOtp', card.data.otp],
      ] as const)
    : []
  return (
    <div role="note" className="flex flex-col gap-2 rounded-md border-l-4 border-warning bg-warning-soft p-4 text-body-sm">
      <div className="flex gap-3">
        <FlaskConical aria-hidden="true" className="size-5 shrink-0 text-warning" />
        <div className="flex flex-col gap-0.5">
          <p className="font-semibold">{t('screen.payment.n13Title')}</p>
          <p>
            {t('screen.payment.n13Body')}{' '}
            <Button
              type="button"
              variant="ghost"
              size="compact"
              className="h-auto min-h-0 p-0 text-primary underline-offset-4 hover:underline"
              aria-expanded={open}
              aria-controls={`${ids}-help`}
              onClick={() => setOpen(!open)}
            >
              {t('screen.payment.n13Help')}
            </Button>
          </p>
        </div>
      </div>
      {open && (
        <div id={`${ids}-help`} className="flex flex-col gap-1 rounded-md bg-card p-3">
          {card.data && !card.data.configured ? (
            <p>{t('screen.payment.helpMissing')}</p>
          ) : (
            <>
              <p>{t('screen.payment.helpLead')}</p>
              <ul className="flex flex-col gap-0.5 font-mono">
                {rows
                  .filter(([, value]) => value)
                  .map(([key, value]) => (
                    <li key={key}>{t(key, { value: value ?? '' })}</li>
                  ))}
              </ul>
              <p className="text-caption text-muted-foreground">{t('screen.payment.helpNote')}</p>
            </>
          )}
        </div>
      )}
    </div>
  )
}
