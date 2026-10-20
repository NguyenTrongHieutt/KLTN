import { Dialog } from '@base-ui/react/dialog'
import { useId, useState } from 'react'
import { inputClass } from '@/components/formField-props'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t, type MessageKey } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatDayMonth } from '@/lib/dates'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { useCancellation, useCancellationCommand, type Cancellation } from './cancellationApi'

/**
 * JOB-1's "Hủy công việc" (FR-40, UX-DR16): either side proposes cancelling the Job or its remaining
 * Milestones with a reason; the other side confirms through a destructive Confirmation dialog that
 * explains the full refund of held money; the proposer may withdraw. After the Agreement or Starting
 * deadline either side may cancel alone (FR-40). The active Crew reads it.
 */
export function CancellationSection({ jobId }: { jobId: string }) {
  const cancellation = useCancellation(jobId)
  const data = cancellation.data
  // An auxiliary part of the overview: nothing while loading, when the read fails, or with nothing to show.
  if (!data || (!data.canPropose && !data.canCancelAlone && (!data.proposal || data.proposal.status !== 'OPEN'))) {
    return null
  }
  return <Content jobId={jobId} data={data} />
}

function failure(error: unknown): string {
  return error instanceof ApiError && error.problem.code === 'STALE'
    ? t('screen.cancellation.stale')
    : problemMessage(error, { module: 'job' }).text
}

function Content({ jobId, data }: { jobId: string; data: Cancellation }) {
  const ids = useId()
  const toasts = useToasts()
  const command = useCancellationCommand(jobId)
  const [proposing, setProposing] = useState(false)
  const [scope, setScope] = useState<'JOB' | 'REMAINING'>('JOB')
  const [reason, setReason] = useState('')
  const [asking, setAsking] = useState(false)
  const [alone, setAlone] = useState(false)
  const [error, setError] = useState<string>()
  const proposal = data.proposal?.status === 'OPEN' ? data.proposal : undefined
  const run = (action: Parameters<typeof command.mutate>[0], success: MessageKey) => {
    setError(undefined)
    command.mutate(action, {
      onSuccess: () => {
        setAsking(false)
        setProposing(false)
        setAlone(false)
        toasts.success(t(success))
      },
      onError: (error) => {
        setAsking(false)
        setError(failure(error))
      },
    })
  }
  const party = data.audience !== 'CREW'
  return (
    <section aria-labelledby={`${ids}-title`} className="flex flex-col gap-3 rounded-lg border border-border bg-card p-5">
      <h2 id={`${ids}-title`} className="text-h2">
        {t('screen.cancellation.heading')}
      </h2>
      <p className="text-body-sm text-muted-foreground">
        {t(data.canCancelAlone ? 'screen.cancellation.aloneLead' : 'screen.cancellation.lead')}
      </p>
      {proposal && (
        <div className="flex flex-col gap-1 rounded-md bg-muted p-3 text-body-sm">
          <span className="text-label">
            {t(`screen.cancellation.openBy.${proposal.proposer}` as MessageKey)} ·{' '}
            {t(proposal.scope === 'REMAINING' ? 'screen.cancellation.scopeREMAINING' : 'screen.cancellation.scopeJOB')}
          </span>
          <p className="break-words whitespace-pre-line">{proposal.reason}</p>
          <span className="text-caption text-muted-foreground">
            {t('screen.cancellation.answerBy', { date: formatDayMonth(proposal.answerBy) })}
          </span>
          {proposal.unanswered && proposal.mine && <p>{t('screen.cancellation.unanswered')}</p>}
        </div>
      )}
      {alone && (
        <fieldset className="flex flex-col gap-2 rounded-md border border-border bg-background p-3 text-body-sm">
          <legend className="px-1 text-label">{t('screen.cancellation.alone')}</legend>
          <label htmlFor={`${ids}-alone`} className="text-label">
            {t('screen.cancellation.reason')}
          </label>
          <textarea
            id={`${ids}-alone`}
            value={reason}
            maxLength={2000}
            onChange={(event) => setReason(event.target.value)}
            className={cn(inputClass, 'min-h-18')}
          />
          <div className="flex flex-wrap justify-end gap-3">
            <Button type="button" variant="ghost" onClick={() => setAlone(false)}>
              {t('screen.cancellation.cancel')}
            </Button>
            <Button
              type="button"
              variant="destructive"
              disabled={reason.trim() === '' || command.isPending}
              onClick={() => setAsking(true)}
            >
              {t('screen.cancellation.aloneSend')}
            </Button>
          </div>
        </fieldset>
      )}
      {proposing && (
        <fieldset className="flex flex-col gap-2 rounded-md border border-border bg-background p-3 text-body-sm">
          <legend className="px-1 text-label">{t('screen.cancellation.scope')}</legend>
          {(['JOB', 'REMAINING'] as const).map((value) => (
            <label key={value} className="flex min-h-tap-min items-center gap-2">
              <input
                type="radio"
                name={`${ids}-scope`}
                className="size-4"
                checked={scope === value}
                onChange={() => setScope(value)}
              />
              {t(value === 'JOB' ? 'screen.cancellation.scopeJOB' : 'screen.cancellation.scopeREMAINING')}
            </label>
          ))}
          <label htmlFor={`${ids}-reason`} className="text-label">
            {t('screen.cancellation.reason')}
          </label>
          <textarea
            id={`${ids}-reason`}
            value={reason}
            maxLength={2000}
            onChange={(event) => setReason(event.target.value)}
            className={cn(inputClass, 'min-h-18')}
          />
          <div className="flex flex-wrap justify-end gap-3">
            <Button type="button" variant="ghost" onClick={() => setProposing(false)}>
              {t('screen.cancellation.cancel')}
            </Button>
            <Button
              type="button"
              variant="destructive"
              disabled={reason.trim() === '' || command.isPending}
              onClick={() =>
                run({ action: 'propose', scope, reason: reason.trim(), version: data.version }, 'screen.cancellation.sent')
              }
            >
              {t('screen.cancellation.send')}
            </Button>
          </div>
        </fieldset>
      )}
      {error && (
        <p role="alert" className="text-body-sm text-destructive">
          {error}
        </p>
      )}
      {party && (
        <div className="flex flex-wrap justify-end gap-3">
          {data.canCancelAlone && !alone && !proposing && (
            <Button type="button" variant="destructive" onClick={() => setAlone(true)}>
              {t('screen.cancellation.alone')}
            </Button>
          )}
          {data.canPropose && !proposing && !alone && (
            <Button type="button" variant="secondary" onClick={() => setProposing(true)}>
              {t('screen.cancellation.propose')}
            </Button>
          )}
          {proposal?.mine && (
            <Button
              type="button"
              variant="ghost"
              disabled={command.isPending}
              onClick={() => run({ action: 'withdraw' }, 'screen.cancellation.withdrawn')}
            >
              {t('screen.cancellation.withdraw')}
            </Button>
          )}
          {proposal && !proposal.mine && (
            <Button type="button" variant="destructive" disabled={command.isPending} onClick={() => setAsking(true)}>
              {t('screen.cancellation.confirm')}
            </Button>
          )}
        </div>
      )}
      <Dialog.Root open={asking} onOpenChange={(next) => !next && setAsking(false)}>
        <Dialog.Portal>
          <Dialog.Backdrop className="fixed inset-0 z-[60] bg-foreground/50" />
          <Dialog.Popup className="fixed top-24 left-1/2 z-[70] flex w-[min(480px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
            <Dialog.Title className="text-h2">
              {t(alone ? 'screen.cancellation.aloneTitle' : 'screen.cancellation.confirmTitle')}
            </Dialog.Title>
            <Dialog.Description className="flex flex-col gap-2 text-body-sm">
              {data.held && <span>{t('screen.cancellation.confirmHeld')}</span>}
              <span>{t('screen.cancellation.confirmPlanned')}</span>
              <span>{t('screen.cancellation.confirmEnd')}</span>
            </Dialog.Description>
            <div className="flex flex-wrap justify-end gap-3">
              <Button type="button" variant="secondary" onClick={() => setAsking(false)}>
                {t('screen.cancellation.cancel')}
              </Button>
              <Button
                type="button"
                variant="destructive"
                disabled={command.isPending}
                onClick={() =>
                  run(
                    alone
                      ? { action: 'alone', reason: reason.trim(), version: data.version }
                      : { action: 'confirm', version: data.version },
                    'screen.cancellation.confirmed',
                  )
                }
              >
                {t(alone ? 'screen.cancellation.aloneSend' : 'screen.cancellation.confirm')}
              </Button>
            </div>
          </Dialog.Popup>
        </Dialog.Portal>
      </Dialog.Root>
    </section>
  )
}
