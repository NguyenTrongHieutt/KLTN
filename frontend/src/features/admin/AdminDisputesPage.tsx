import { useId, useState } from 'react'
import { Link, useParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { inputClass } from '@/components/formField-props'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t, type MessageKey } from '@/i18n/t'
import { formatDayMonthTime } from '@/lib/dates'
import { formatVnd } from '@/lib/money'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { useAdminDispute, useAdminDisputes, useDisputeCommand, type AdminDispute } from './adminDisputesApi'
import { CaseTabs } from './CaseTabs'
import { DisputeEvidence } from './DisputeEvidence'
import { StepUpDialog } from './StepUpDialog'

type Next = 'CONTINUE' | 'END' | 'CANCEL'

/**
 * ADM-4 (FR-54, AD-20, AD-31, UX-DR58): the Disputes, and one Dispute with its Held Milestone, "Nhận xử
 * lý", the decision form (release and refund that sum to what is held, the next state, the reasoning),
 * the summary, the step-up, and the execution "Bước {k}/4" with "Thử lại". Totals only. The assignee reads
 * the Evidence Package until 30 days after the decision (Story 15.2).
 */
export function AdminDisputesPage() {
  const { disputeId } = useParams()
  usePageTitle(t('screen.admin.disputes.title'))
  return (
    <div className="flex flex-col gap-5">
      <PageHeading>{t('screen.admin.disputes.title')}</PageHeading>
      <CaseTabs current="disputes" />
      <div className="grid gap-5 lg:grid-cols-[minmax(0,320px)_minmax(0,1fr)]">
        <DisputeList current={disputeId} />
        {disputeId && <DisputeDetail key={disputeId} disputeId={disputeId} />}
      </div>
    </div>
  )
}

function DisputeList({ current }: { current?: string }) {
  const disputes = useAdminDisputes()
  const [onlyLong, setOnlyLong] = useState(false)
  const rows = (disputes.data ?? []).filter((row) => !onlyLong || row.undecidedLong)
  return (
    <RegionState query={disputes} skeleton={<Skeleton className="h-64 w-full" />}>
      <div role="group" aria-label={t('screen.admin.disputes.filterLong')} className="mb-2 flex flex-wrap gap-2">
        {[false, true].map((option) => (
          <button
            key={String(option)}
            type="button"
            aria-pressed={onlyLong === option}
            onClick={() => setOnlyLong(option)}
            className={cn(
              'min-h-control-compact rounded-full border px-3 text-label focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring',
              onlyLong === option ? 'border-primary bg-primary-soft text-foreground' : 'border-border bg-card text-muted-foreground hover:bg-muted',
            )}
          >
            {t(option ? 'screen.admin.disputes.filterLong' : 'screen.admin.disputes.filterAll')}
          </button>
        ))}
      </div>
      {disputes.data &&
        (rows.length === 0 ? (
          <p className="text-body-sm text-muted-foreground">{t('screen.admin.disputes.empty')}</p>
        ) : (
          <ul className="flex flex-col gap-2">
            {rows.map((row) => (
              <li key={row.id}>
                <Link
                  to={`/quan-tri/tranh-chap/${row.id}`}
                  aria-current={row.id === current ? 'page' : undefined}
                  className={cn(
                    'flex flex-col gap-0.5 rounded-md border border-border bg-card p-3 text-body-sm',
                    row.id === current && 'border-primary',
                  )}
                >
                  <span className="text-label font-semibold break-words">{row.jobTitle}</span>
                  <span className="text-caption text-muted-foreground">
                    {[
                      t(`screen.admin.disputes.openedBy.${row.openedBy}` as MessageKey),
                      formatDayMonthTime(row.openedAt),
                      t(`screen.admin.disputes.status.${row.status}` as MessageKey),
                      row.assignedToMe ? t('screen.admin.disputes.assignedToMe') : row.assigned ? t('screen.admin.disputes.assigned') : '',
                      row.hasStatement ? t('screen.admin.disputes.hasStatement') : '',
                    ]
                      .filter((part) => part !== '')
                      .join(' · ')}
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        ))}
    </RegionState>
  )
}

function DisputeDetail({ disputeId }: { disputeId: string }) {
  const dispute = useAdminDispute(disputeId)
  return (
    <RegionState query={dispute} skeleton={<Skeleton className="h-96 w-full" />}>
      {dispute.data && <Detail data={dispute.data} />}
    </RegionState>
  )
}

function Detail({ data }: { data: AdminDispute }) {
  const toasts = useToasts()
  const command = useDisputeCommand(data.id)
  const [error, setError] = useState<string>()
  const fail = (failure: unknown) => setError(problemMessage(failure, { module: 'dispute' }).text)
  return (
    <section className="flex flex-col gap-4 rounded-lg border border-border bg-card p-5 text-body-sm">
      <h2 className="text-h2 break-words">{data.jobTitle}</h2>
      <p className="text-caption text-muted-foreground">
        {t(`screen.admin.disputes.openedBy.${data.openedBy}` as MessageKey)} · {formatDayMonthTime(data.openedAt)} ·{' '}
        {t(`screen.admin.disputes.concern.${data.concern}` as MessageKey)}
      </p>
      <div className="flex flex-col gap-1">
        <span className="text-label">{t('screen.admin.disputes.reason')}</span>
        <p className="break-words whitespace-pre-line">{data.reason}</p>
      </div>
      <p>
        {data.held
          ? t('screen.admin.disputes.held', {
              n: data.held.number,
              title: data.held.title,
              amount: formatVnd(data.held.releasableVnd),
            })
          : t('screen.admin.disputes.noHeld')}
      </p>
      {data.statements.map((statement) => (
        <div key={statement.side} className="flex flex-col gap-1 rounded-md bg-muted p-3">
          <span className="text-caption text-muted-foreground">
            {t(`screen.admin.disputes.statementBy.${statement.side}` as MessageKey, { time: formatDayMonthTime(statement.submittedAt) })}
          </span>
          <p className="break-words whitespace-pre-line">{statement.text}</p>
        </div>
      ))}
      {data.status === 'OPEN' && data.statements.length > 0 && (
        <p className="text-caption font-semibold">{t('screen.admin.disputes.hasStatement')}</p>
      )}
      {data.status === 'OPEN' && data.statements.length === 0 && data.decidable && (
        <p className="text-caption text-muted-foreground">{t('screen.admin.disputes.noStatement')}</p>
      )}
      {data.evidenceReadable ? (
        <DisputeEvidence data={data} />
      ) : (
        <p className="text-caption text-muted-foreground">
          {t(data.status === 'OPEN' ? 'screen.admin.disputes.evidence.takeFirst' : 'screen.admin.disputes.evidence.closed')}
        </p>
      )}
      {error && (
        <p role="alert" className="text-destructive">
          {error}
        </p>
      )}
      {data.decision ? (
        <Outcome data={data} onRetry={() => command.mutate({ action: 'retry' }, { onError: fail })} pending={command.isPending} />
      ) : data.status === 'OPEN' && !data.assignedToMe ? (
        <div>
          <Button
            type="button"
            disabled={command.isPending}
            onClick={() => command.mutate({ action: 'take' }, { onSuccess: () => setError(undefined), onError: fail })}
          >
            {t('screen.admin.disputes.take')}
          </Button>
        </div>
      ) : data.status === 'OPEN' && !data.decidable ? (
        <p className="rounded-md bg-muted p-3">
          {t('screen.admin.disputes.waitStatement', { date: formatDayMonthTime(data.statementDueAt) })}
        </p>
      ) : (
        data.status === 'OPEN' && (
          <DecisionForm
            data={data}
            pending={command.isPending}
            onDecide={(body, refused) =>
              command.mutate(
                { action: 'decide', body },
                {
                  onSuccess: () => toasts.success(t('screen.admin.disputes.decided')),
                  onError: refused,
                },
              )
            }
          />
        )
      )}
    </section>
  )
}

function Outcome({ data, onRetry, pending }: { data: AdminDispute; onRetry: () => void; pending: boolean }) {
  const decision = data.decision!
  const execution = data.execution
  return (
    <div className="flex flex-col gap-2 rounded-md bg-muted p-3">
      <span className="text-label">{t('screen.admin.disputes.decision')}</span>
      <p>
        {t('screen.admin.disputes.summary', {
          release: formatVnd(decision.releaseVnd ?? 0),
          refund: formatVnd(decision.refundVnd ?? 0),
          next: t(`screen.admin.disputes.nextOption.${decision.nextState}` as MessageKey),
        })}
      </p>
      <p className="break-words whitespace-pre-line">{decision.reasoning}</p>
      {execution && (
        <p className="text-caption">
          {t('screen.admin.disputes.step', { k: Math.min(4, execution.step + (execution.status === 'DONE' ? 0 : 1)) })} ·{' '}
          {t(`screen.admin.disputes.executionStatus.${execution.status}` as MessageKey)}
        </p>
      )}
      {execution?.status === 'EXECUTION_FAILED' && (
        <>
          <p className="text-caption text-destructive break-words">
            {t('screen.admin.disputes.failure', { failure: execution.failure ?? '' })}
          </p>
          <div>
            <Button type="button" disabled={pending} onClick={onRetry}>
              {t('screen.admin.disputes.retry')}
            </Button>
          </div>
        </>
      )}
    </div>
  )
}

function DecisionForm({
  data,
  pending,
  onDecide,
}: {
  data: AdminDispute
  pending: boolean
  onDecide: (
    body: { releaseVnd?: number; refundVnd?: number; nextState: Next; reasoning: string; stepUpCode: string },
    refused: (error: unknown) => void,
  ) => void
}) {
  const ids = useId()
  const held = data.held?.releasableVnd
  const [release, setRelease] = useState('')
  const [refund, setRefund] = useState('')
  const [next, setNext] = useState<Next>(data.plannedRemains ? 'CONTINUE' : 'END')
  const [reasoning, setReasoning] = useState('')
  const [reviewing, setReviewing] = useState(false)
  const [stepUp, setStepUp] = useState(false)
  const releaseVnd = Number(release || 0)
  const refundVnd = Number(refund || 0)
  const sum = releaseVnd + refundVnd
  const amountsOk = held === undefined || (Number.isInteger(releaseVnd) && Number.isInteger(refundVnd) && sum === held)
  const ready = amountsOk && reasoning.trim() !== ''
  const options: Next[] = data.plannedRemains ? ['CONTINUE', 'END', 'CANCEL'] : ['END', 'CANCEL']
  return (
    <fieldset className="flex flex-col gap-3 rounded-md border border-border bg-background p-3">
      <legend className="px-1 text-label">{t('screen.admin.disputes.decision')}</legend>
      {held !== undefined && (
        <>
          <label htmlFor={`${ids}-release`} className="text-label">
            {t('screen.admin.disputes.release')}
          </label>
          <input
            id={`${ids}-release`}
            inputMode="numeric"
            value={release}
            onChange={(event) => setRelease(event.target.value.replace(/[^0-9]/g, ''))}
            className={inputClass}
          />
          <label htmlFor={`${ids}-refund`} className="text-label">
            {t('screen.admin.disputes.refund')}
          </label>
          <input
            id={`${ids}-refund`}
            inputMode="numeric"
            value={refund}
            onChange={(event) => setRefund(event.target.value.replace(/[^0-9]/g, ''))}
            className={inputClass}
          />
          <p role="status" className={cn('text-caption', amountsOk ? 'text-success' : 'text-destructive')}>
            {t('screen.admin.disputes.sum', { sum: formatVnd(sum), held: formatVnd(held) })} ·{' '}
            {t(amountsOk ? 'screen.admin.disputes.sumOk' : 'screen.admin.disputes.sumMismatch')}
          </p>
        </>
      )}
      <p className="text-caption text-muted-foreground">{t('screen.admin.disputes.merge')}</p>
      <fieldset className="flex flex-col gap-1">
        <legend className="text-label">{t('screen.admin.disputes.next')}</legend>
        <div className="flex flex-wrap gap-x-4">
          {options.map((option) => (
            <label key={option} className="flex min-h-tap-min items-center gap-2">
              <input
                type="radio"
                name={`${ids}-next`}
                className="size-4"
                checked={next === option}
                onChange={() => setNext(option)}
              />
              {t(`screen.admin.disputes.nextOption.${option}` as MessageKey)}
            </label>
          ))}
        </div>
      </fieldset>
      <label htmlFor={`${ids}-reasoning`} className="text-label">
        {t('screen.admin.disputes.reasoning')}
      </label>
      <textarea
        id={`${ids}-reasoning`}
        value={reasoning}
        maxLength={4000}
        onChange={(event) => setReasoning(event.target.value)}
        className={cn(inputClass, 'min-h-24')}
      />
      {reviewing && (
        <p className="rounded-md bg-muted p-3 text-body-sm">
          {t('screen.admin.disputes.summary', {
            release: formatVnd(held === undefined ? 0 : releaseVnd),
            refund: formatVnd(held === undefined ? 0 : refundVnd),
            next: t(`screen.admin.disputes.nextOption.${next}` as MessageKey),
          })}
        </p>
      )}
      <div className="flex justify-end">
        {reviewing ? (
          <Button type="button" variant="destructive" disabled={!ready || pending} onClick={() => setStepUp(true)}>
            {t('screen.admin.disputes.confirm')}
          </Button>
        ) : (
          <Button type="button" disabled={!ready} onClick={() => setReviewing(true)}>
            {t('screen.admin.disputes.review')}
          </Button>
        )}
      </div>
      <StepUpDialog
        open={stepUp}
        onOpenChange={setStepUp}
        confirmLabel={t('screen.admin.disputes.confirm')}
        pending={pending}
        onConfirm={(code, refused) =>
          onDecide(
            held === undefined
              ? { nextState: next, reasoning: reasoning.trim(), stepUpCode: code }
              : { releaseVnd, refundVnd, nextState: next, reasoning: reasoning.trim(), stepUpCode: code },
            refused,
          )
        }
      />
    </fieldset>
  )
}
