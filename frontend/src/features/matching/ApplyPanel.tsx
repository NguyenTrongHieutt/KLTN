import { Dialog } from '@base-ui/react/dialog'
import { X } from 'lucide-react'
import { useId, useState, type FormEvent, type ReactNode } from 'react'
import { inputClass } from '@/components/formField-props'
import { useToasts } from '@/components/toasts'
import { TestTag } from '@/components/TestTag'
import { Button } from '@/components/ui/button'
import { t, type MessageKey } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatDayMonthTime } from '@/lib/dates'
import { formatVnd, groupDigits, parseVnd } from '@/lib/money'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { isLive, useApply, useMyApplications, useWithdrawApplication, type Application } from './applicationApi'

/**
 * MEN-3's "Ứng tuyển" (FR-13, UX-DR8, UX-DR20): the price in whole VND, formatted while typing and
 * tagged as test money, the days, the approach in plain words, and N-5. After applying, the Mentor
 * sees their Application and can withdraw it before selection.
 */
export function ApplyPanel({ postId, teamSize, blocked }: { postId: string; teamSize: number; blocked: boolean }) {
  const mine = useMyApplications()
  const existing = mine.data?.find((application) => application.jobId === postId)
  return (
    <section aria-labelledby="apply-heading" className="flex flex-col gap-3 rounded-lg border border-border bg-card p-5">
      <h2 id="apply-heading" className="text-h2">
        {t('screen.matching.apply.heading')}
      </h2>
      {mine.isPending ? null : existing ? (
        <Applied application={existing} />
      ) : (
        <ApplyForm postId={postId} teamSize={teamSize} blocked={blocked} />
      )}
    </section>
  )
}

function ApplyForm({ postId, teamSize, blocked }: { postId: string; teamSize: number; blocked: boolean }) {
  const ids = useId()
  const apply = useApply(postId)
  const toasts = useToasts()
  const [price, setPrice] = useState('')
  const [days, setDays] = useState('')
  const [approach, setApproach] = useState('')
  const [acknowledged, setAcknowledged] = useState(false)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const submit = (event: FormEvent) => {
    event.preventDefault()
    const priceVnd = parseVnd(price)
    const timelineDays = Number.parseInt(days, 10)
    const found: Record<string, string> = {}
    if (priceVnd === undefined || priceVnd < 1000 || priceVnd > 1_000_000_000) {
      found.price = t('screen.matching.apply.priceInvalid')
    }
    if (!Number.isInteger(timelineDays) || timelineDays < 1 || timelineDays > 365) {
      found.days = t('screen.matching.apply.daysInvalid')
    }
    if (approach.trim() === '') {
      found.approach = t('screen.matching.apply.approachRequired')
    }
    if (!acknowledged) {
      found.acknowledged = t('screen.matching.apply.acknowledgeRequired')
    }
    setErrors(found)
    if (Object.keys(found).length > 0 || priceVnd === undefined) {
      return
    }
    apply.mutate(
      { priceVnd, timelineDays, approach: approach.trim(), publicCodeAcknowledged: true },
      {
        onSuccess: () => toasts.success(t('screen.matching.apply.sent')),
        onError: (error) => toasts.error(refusal(error), problemMessage(error, { module: 'matching' }).link),
      },
    )
  }
  return (
    <form onSubmit={submit} noValidate className="flex flex-col gap-4">
      <p className="text-body-sm text-muted-foreground">{t('screen.matching.apply.lead', { n: teamSize })}</p>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field id={`${ids}-price`} label={t('screen.matching.apply.price')} error={errors.price}>
          <div className="relative">
            <input
              id={`${ids}-price`}
              type="text"
              inputMode="numeric"
              autoComplete="off"
              value={groupDigits(price)}
              onChange={(event) => setPrice(event.target.value.replace(/\D/g, '').slice(0, 10))}
              aria-describedby={`${ids}-price-hint${errors.price ? ` ${ids}-price-error` : ''}`}
              aria-invalid={errors.price ? true : undefined}
              className={cn(inputClass, 'pr-10')}
            />
            <span aria-hidden="true" className="pointer-events-none absolute top-1/2 right-3 -translate-y-1/2 text-muted-foreground">
              ₫
            </span>
          </div>
          <p id={`${ids}-price-hint`} className="text-caption text-muted-foreground">
            <TestTag />{' '}
            {t('screen.matching.apply.priceHint')}
          </p>
        </Field>
        <Field id={`${ids}-days`} label={t('screen.matching.apply.days')} error={errors.days}>
          <input
            id={`${ids}-days`}
            type="number"
            inputMode="numeric"
            min={1}
            max={365}
            value={days}
            onChange={(event) => setDays(event.target.value)}
            aria-describedby={errors.days ? `${ids}-days-error` : undefined}
            aria-invalid={errors.days ? true : undefined}
            className={inputClass}
          />
        </Field>
      </div>
      <Field id={`${ids}-approach`} label={t('screen.matching.apply.approach')} error={errors.approach}>
        <p id={`${ids}-approach-hint`} className="text-caption text-muted-foreground">
          {t('screen.matching.apply.approachHint')}
        </p>
        <textarea
          id={`${ids}-approach`}
          rows={4}
          maxLength={2000}
          value={approach}
          onChange={(event) => setApproach(event.target.value)}
          aria-describedby={`${ids}-approach-hint${errors.approach ? ` ${ids}-approach-error` : ''}`}
          aria-invalid={errors.approach ? true : undefined}
          className={cn(inputClass, 'min-h-24')}
        />
      </Field>
      <div className="flex flex-col gap-1">
        <label className="flex items-start gap-2 text-body-sm">
          <input
            type="checkbox"
            className="mt-1"
            checked={acknowledged}
            onChange={(event) => setAcknowledged(event.target.checked)}
            aria-invalid={errors.acknowledged ? true : undefined}
          />
          <span>{t('screen.matching.apply.n5')}</span>
        </label>
        {errors.acknowledged && (
          <p role="alert" className="text-body-sm text-destructive">
            {errors.acknowledged}
          </p>
        )}
      </div>
      <Button type="submit" className="w-fit" disabled={blocked || apply.isPending}>
        {t(apply.isPending ? 'screen.matching.apply.sending' : 'screen.matching.apply.submit')}
      </Button>
    </form>
  )
}

function Field({
  id,
  label,
  error,
  children,
}: {
  id: string
  label: string
  error?: string
  children: ReactNode
}) {
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-label font-semibold">
        {label}
      </label>
      {children}
      {error && (
        <p id={`${id}-error`} role="alert" className="text-body-sm text-destructive">
          {error}
        </p>
      )}
    </div>
  )
}

/** The Mentor's Application, with "Rút đơn" while it can still be withdrawn. */
function Applied({ application }: { application: Application }) {
  const [confirming, setConfirming] = useState(false)
  const withdraw = useWithdrawApplication()
  const toasts = useToasts()
  const live = isLive(application)
  const confirm = () =>
    withdraw.mutate(application.id, {
      onSuccess: () => {
        setConfirming(false)
        toasts.success(t('screen.matching.applied.withdrawn'))
      },
      onError: (error) => {
        setConfirming(false)
        toasts.error(problemMessage(error, { module: 'matching' }).text)
      },
    })
  return (
    <div className="flex flex-col gap-2">
      <p className="text-label font-semibold">
        {t(live ? 'screen.matching.applied.title' : (`screen.matching.applied.ended${application.status}` as MessageKey))}
      </p>
      <p className="text-body-sm">
        {formatVnd(application.priceVnd)}{' '}
        <TestTag />
        {' · '}
        {t('screen.matching.days', { n: application.timelineDays })}
      </p>
      <p className="text-body-sm break-words whitespace-pre-line text-muted-foreground">{application.approach}</p>
      {(application.status === 'SUBMITTED' || application.status === 'ON_HOLD') && (
        <Button type="button" variant="secondary" className="w-fit" onClick={() => setConfirming(true)}>
          {t('screen.matching.applied.withdraw')}
        </Button>
      )}
      <Dialog.Root open={confirming} onOpenChange={setConfirming}>
        <Dialog.Portal>
          <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
          <Dialog.Popup className="fixed top-24 left-1/2 z-50 flex w-[min(520px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
            <div className="flex items-start justify-between gap-3">
              <Dialog.Title className="text-h2">{t('screen.matching.applied.withdrawTitle')}</Dialog.Title>
              <Dialog.Close
                aria-label={t('screen.product.finding.close')}
                className="rounded-md p-1 text-muted-foreground hover:bg-muted"
              >
                <X aria-hidden="true" className="size-5" />
              </Dialog.Close>
            </div>
            <Dialog.Description className="text-body-sm">{t('screen.matching.applied.withdrawBody')}</Dialog.Description>
            <div className="flex flex-wrap justify-between gap-3">
              <Dialog.Close render={<Button variant="secondary" />}>{t('screen.matching.applied.keep')}</Dialog.Close>
              <Button type="button" onClick={confirm} disabled={withdraw.isPending}>
                {t('screen.matching.applied.withdraw')}
              </Button>
            </div>
          </Dialog.Popup>
        </Dialog.Portal>
      </Dialog.Root>
    </div>
  )
}

/** The plain refusal (FR-13): the Job limit names the limit, and too many today names when to try again. */
function refusal(error: unknown): string {
  if (error instanceof ApiError && error.problem.reason === 'MENTOR_JOB_LIMIT') {
    const limit = (error.problem as { limit?: number }).limit ?? 3
    return t('screen.jobPost.jobLimit', { n: limit })
  }
  if (error instanceof ApiError && error.status === 429) {
    const at = new Date(Date.now() + (error.retryAfter ?? 0) * 1000).toISOString()
    return t('screen.matching.apply.tooMany', { time: formatDayMonthTime(at) })
  }
  return problemMessage(error, { module: 'matching' }).text
}
