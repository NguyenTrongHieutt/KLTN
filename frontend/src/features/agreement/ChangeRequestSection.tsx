import { Dialog } from '@base-ui/react/dialog'
import { useId, useState } from 'react'
import { inputClass } from '@/components/formField-props'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t, type MessageKey } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatCalendarDate, formatDayMonthTime } from '@/lib/dates'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import {
  useChangeRequestCommand,
  useChangeRequests,
  useConfirmedVersions,
  type ChangeRequest,
  type ChangeRequests,
} from './changeRequestApi'

/**
 * JOB-6's "Đề nghị thay đổi" in its due-date variant (FR-36, UX-DR57): either side proposes new due dates
 * for Milestones that are not final; the Mentor states the time impact; the Customer approves and the
 * Mentor confirms, each through the Confirmation dialog on the loaded Job version; then the new dates and
 * Agreement version are in force. Every confirmed version stays listed (AD-29).
 */
export function ChangeRequestSection({ jobId }: { jobId: string }) {
  const requests = useChangeRequests(jobId)
  // An auxiliary part of the Agreement page: nothing while loading or when the read fails.
  return requests.data ? <Content jobId={jobId} data={requests.data} /> : null
}

function due(date: string | null | undefined): string {
  return date ? formatCalendarDate(date) : t('screen.changeRequest.noDue')
}

function failure(error: unknown): string {
  return error instanceof ApiError && error.problem.code === 'STALE'
    ? t('screen.changeRequest.stale')
    : problemMessage(error, { module: 'job' }).text
}

function Content({ jobId, data }: { jobId: string; data: ChangeRequests }) {
  const ids = useId()
  const [proposing, setProposing] = useState(false)
  return (
    <section
      id="doi-han"
      aria-labelledby={`${ids}-title`}
      className="flex w-full max-w-[820px] flex-col gap-3 self-center rounded-lg border border-border bg-card p-5"
    >
      <h2 id={`${ids}-title`} className="text-h2">
        {t('screen.changeRequest.heading')}
      </h2>
      <p className="text-body-sm text-muted-foreground">{t('screen.changeRequest.lead')}</p>
      {data.open && <OpenRequest jobId={jobId} data={data} request={data.open} />}
      {data.canPropose &&
        (proposing ? (
          <ProposalForm jobId={jobId} data={data} onDone={() => setProposing(false)} />
        ) : (
          <div>
            <Button type="button" variant="secondary" onClick={() => setProposing(true)}>
              {t('screen.changeRequest.propose')}
            </Button>
          </div>
        ))}
      {data.history.length > 0 && (
        <div className="flex flex-col gap-2">
          <h3 className="text-h3">{t('screen.changeRequest.history')}</h3>
          <ul className="flex flex-col gap-2">
            {data.history.map((request) => (
              <li key={request.id} className="flex flex-col gap-1 rounded-md bg-muted p-3 text-body-sm">
                <Changes request={request} />
                <span className="text-caption text-muted-foreground">
                  {t(`screen.changeRequest.status.${request.status}` as MessageKey, {
                    version: request.agreementVersionNo ?? '',
                  })}
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}
      <Versions jobId={jobId} />
    </section>
  )
}

function Changes({ request }: { request: ChangeRequest }) {
  return (
    <>
      <span className="text-label">{t(`screen.changeRequest.openBy.${request.proposer}` as MessageKey)}</span>
      <ul className="flex flex-col gap-0.5">
        {request.items.map((item) => (
          <li key={item.milestoneId}>
            {t('screen.changeRequest.change', { n: item.number, from: due(item.oldDueOn), to: due(item.newDueOn) })}
          </li>
        ))}
      </ul>
      {request.description && <p className="break-words whitespace-pre-line">{request.description}</p>}
      {request.impact && <p className="break-words">{t('screen.changeRequest.impactLabel', { impact: request.impact })}</p>}
    </>
  )
}

function OpenRequest({ jobId, data, request }: { jobId: string; data: ChangeRequests; request: ChangeRequest }) {
  const ids = useId()
  const toasts = useToasts()
  const command = useChangeRequestCommand(jobId)
  const [impact, setImpact] = useState('')
  const [asking, setAsking] = useState<'approve' | 'confirm'>()
  const [error, setError] = useState<string>()
  const customer = data.audience === 'CUSTOMER'
  const run = (action: Parameters<typeof command.mutate>[0]) => {
    setError(undefined)
    command.mutate(action, {
      onSuccess: () => {
        setAsking(undefined)
        toasts.success(t('screen.changeRequest.sent'))
      },
      onError: (error) => {
        setAsking(undefined)
        setError(failure(error))
      },
    })
  }
  const needsImpact = request.impact == null
  const approved = request.customerApprovedAt != null
  return (
    <div className="flex flex-col gap-2 rounded-md border border-border bg-background p-3 text-body-sm">
      <Changes request={request} />
      {needsImpact &&
        (customer ? (
          <p className="text-muted-foreground">{t('screen.changeRequest.waitingImpact')}</p>
        ) : (
          <div className="flex flex-col gap-2">
            <label htmlFor={`${ids}-impact`} className="text-label">
              {t('screen.changeRequest.impact')}
            </label>
            <textarea
              id={`${ids}-impact`}
              value={impact}
              maxLength={1000}
              onChange={(event) => setImpact(event.target.value)}
              className={cn(inputClass, 'min-h-18')}
            />
            <div>
              <Button
                type="button"
                disabled={impact.trim() === '' || command.isPending}
                onClick={() => run({ action: 'impact', requestId: request.id, impact: impact.trim() })}
              >
                {t('screen.changeRequest.addImpact')}
              </Button>
            </div>
          </div>
        ))}
      {!needsImpact && !approved && !customer && (
        <p className="text-muted-foreground">{t('screen.changeRequest.waitingCustomer')}</p>
      )}
      {approved && customer && <p className="text-muted-foreground">{t('screen.changeRequest.approved')}</p>}
      {error && (
        <p role="alert" className="text-destructive">
          {error}
        </p>
      )}
      <div className="flex flex-wrap justify-end gap-3">
        {request.mine && (
          <Button
            type="button"
            variant="ghost"
            disabled={command.isPending}
            onClick={() => run({ action: 'withdraw', requestId: request.id })}
          >
            {t('screen.changeRequest.withdraw')}
          </Button>
        )}
        {customer && !approved && !needsImpact && (
          <Button type="button" disabled={command.isPending} onClick={() => setAsking('approve')}>
            {t('screen.changeRequest.approve')}
          </Button>
        )}
        {!customer && approved && (
          <Button type="button" disabled={command.isPending} onClick={() => setAsking('confirm')}>
            {t('screen.changeRequest.confirm')}
          </Button>
        )}
      </div>
      <Dialog.Root open={asking !== undefined} onOpenChange={(next) => !next && setAsking(undefined)}>
        <Dialog.Portal>
          <Dialog.Backdrop className="fixed inset-0 z-[60] bg-foreground/50" />
          <Dialog.Popup className="fixed top-24 left-1/2 z-[70] flex w-[min(480px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
            <Dialog.Title className="text-h2">{t('screen.changeRequest.confirmTitle')}</Dialog.Title>
            <Dialog.Description className="text-body-sm">{t('screen.changeRequest.confirmBody')}</Dialog.Description>
            <div className="flex flex-wrap justify-end gap-3">
              <Button type="button" variant="secondary" onClick={() => setAsking(undefined)}>
                {t('screen.changeRequest.cancel')}
              </Button>
              <Button
                type="button"
                disabled={command.isPending}
                onClick={() => asking && run({ action: asking, requestId: request.id, version: data.version })}
              >
                {t(asking === 'confirm' ? 'screen.changeRequest.confirm' : 'screen.changeRequest.approve')}
              </Button>
            </div>
          </Dialog.Popup>
        </Dialog.Portal>
      </Dialog.Root>
    </div>
  )
}

function ProposalForm({ jobId, data, onDone }: { jobId: string; data: ChangeRequests; onDone: () => void }) {
  const ids = useId()
  const toasts = useToasts()
  const command = useChangeRequestCommand(jobId)
  const [dates, setDates] = useState<Record<string, string>>({})
  const [description, setDescription] = useState('')
  const [impact, setImpact] = useState('')
  const [error, setError] = useState<string>()
  const customer = data.audience === 'CUSTOMER'
  const items = Object.entries(dates)
    .filter(([, date]) => date !== '')
    .map(([milestoneId, newDueOn]) => ({ milestoneId, newDueOn }))
  const ready = items.length > 0 && (customer ? description.trim() !== '' : impact.trim() !== '')
  const send = () => {
    setError(undefined)
    command.mutate(
      {
        action: 'propose',
        body: {
          items,
          version: data.version,
          ...(customer ? { description: description.trim() } : { impact: impact.trim() }),
        },
      },
      {
        onSuccess: () => {
          toasts.success(t('screen.changeRequest.sent'))
          onDone()
        },
        onError: (error) => setError(failure(error)),
      },
    )
  }
  return (
    <fieldset className="flex flex-col gap-3 rounded-md border border-border bg-background p-3 text-body-sm">
      <legend className="px-1 text-label">{t('screen.changeRequest.propose')}</legend>
      <ul className="flex flex-col gap-2">
        {data.milestones.map((milestone) => (
          <li key={milestone.id} className="flex flex-wrap items-center justify-between gap-2">
            <span className="flex flex-col">
              <span className="text-label">
                {t('screen.changeRequest.milestone', { n: milestone.number, title: milestone.title })}
              </span>
              <span className="text-caption text-muted-foreground">
                {t('screen.changeRequest.currentDue', { date: due(milestone.dueOn) })}
              </span>
            </span>
            {milestone.changeable ? (
              <label htmlFor={`${ids}-${milestone.id}`} className="flex items-center gap-2">
                <span className="text-caption">{t('screen.changeRequest.newDue')}</span>
                <input
                  id={`${ids}-${milestone.id}`}
                  type="date"
                  value={dates[milestone.id] ?? ''}
                  onChange={(event) => setDates((current) => ({ ...current, [milestone.id]: event.target.value }))}
                  className={cn(inputClass, 'w-44')}
                  aria-label={`${t('screen.changeRequest.newDue')} ${milestone.number}`}
                />
              </label>
            ) : (
              <span className="text-caption text-muted-foreground">{t('screen.changeRequest.final')}</span>
            )}
          </li>
        ))}
      </ul>
      <label htmlFor={`${ids}-text`} className="text-label">
        {t(customer ? 'screen.changeRequest.description' : 'screen.changeRequest.impact')}
      </label>
      <textarea
        id={`${ids}-text`}
        value={customer ? description : impact}
        maxLength={customer ? 2000 : 1000}
        onChange={(event) => (customer ? setDescription(event.target.value) : setImpact(event.target.value))}
        className={cn(inputClass, 'min-h-18')}
      />
      {error && (
        <p role="alert" className="text-destructive">
          {error}
        </p>
      )}
      <div className="flex flex-wrap justify-end gap-3">
        <Button type="button" variant="ghost" onClick={onDone}>
          {t('screen.changeRequest.cancel')}
        </Button>
        <Button type="button" disabled={!ready || command.isPending} onClick={send}>
          {t('screen.changeRequest.send')}
        </Button>
      </div>
    </fieldset>
  )
}

function Versions({ jobId }: { jobId: string }) {
  const versions = useConfirmedVersions(jobId)
  if (!versions.data || versions.data.versions.length < 2) {
    return null
  }
  return (
    <div className="flex flex-col gap-2">
      <h3 className="text-h3">{t('screen.changeRequest.versions')}</h3>
      <ul className="flex flex-col gap-2">
        {versions.data.versions.map((version) => (
          <li key={version.versionNo} className="flex flex-col gap-0.5 rounded-md bg-muted p-3 text-body-sm">
            <span className="text-label">
              {t('screen.changeRequest.version', { n: version.versionNo, date: formatDayMonthTime(version.confirmedAt) })}
            </span>
            {version.milestones.map((milestone, index) => (
              <span key={milestone.id} className="text-caption">
                {t('screen.changeRequest.milestone', { n: index + 1, title: milestone.title })}: {due(milestone.dueOn)}
              </span>
            ))}
          </li>
        ))}
      </ul>
    </div>
  )
}
