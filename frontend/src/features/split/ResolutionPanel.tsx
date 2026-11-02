import { useId, useState } from 'react'
import { Dialog } from '@base-ui/react/dialog'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { inputClass } from '@/components/formField-props'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatDate, formatDayMonth, formatDayMonthTime } from '@/lib/dates'
import { problemMessage, sentence } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import {
  useRaiseResolution,
  useResolutionStatement,
  useSplitResolutions,
  useWithdrawResolution,
  type SplitCard,
  type SplitResolution,
} from './splitApi'

const MOST = 2000

/** A refusal in plain words: the raise's `SPLIT_FINAL` has EXPERIENCE.md's own text for a split past its due time. */
function refusal(error: unknown): string {
  if (error instanceof ApiError) {
    const problem: ApiError['problem'] & { pastDue?: boolean } = error.problem
    if (problem.reason === 'SPLIT_FINAL' && problem.pastDue) {
      return sentence(t('reason.dispute.SPLIT_FINAL_PAST_DUE'))
    }
  }
  return problemMessage(error, { module: 'dispute' }).text
}

function isStale(error: unknown): boolean {
  return error instanceof ApiError && error.problem.code === 'STALE'
}

/**
 * The Split Resolutions on a card (FR-84, UX-DR106, A-18): every case of the Milestone with its reason and statements
 * for every party, the Mentor included; "Nhờ quản trị viên xem xét" where the server says the reader may raise one;
 * another party sees the open case in place of the button, with one statement form; the raiser's "Rút yêu cầu"; the
 * reasoning once decided.
 */
export function ResolutionPanel({ jobId, card }: { jobId: string; card: SplitCard }) {
  const relevant = card.finality !== 'MENTOR_ONLY' && card.status !== 'AWAITING_DRAFT'
  const resolutions = useSplitResolutions(card.splitId, relevant, relevant && card.status !== 'FINAL')
  const data = resolutions.data
  if (!relevant || !data) {
    return null
  }
  return (
    <div className="flex flex-col gap-3">
      {data.viewerMayRaise && <RaiseResolution jobId={jobId} card={card} />}
      {data.resolutions.map((resolution, index) => (
        <ResolutionCase key={resolution.id} jobId={jobId} card={card} resolution={resolution} latest={index === 0} />
      ))}
    </div>
  )
}

/** "Nhờ quản trị viên xem xét" with the Resolution request dialog: N-31 and a required reason. */
function RaiseResolution({ jobId, card }: { jobId: string; card: SplitCard }) {
  const ids = useId()
  const raise = useRaiseResolution(jobId, card.splitId)
  const [open, setOpen] = useState(false)
  const [reason, setReason] = useState('')
  const [stale, setStale] = useState(false)
  const version = card.latest
  if (!version) {
    return null
  }
  // N-31 keeps its cap clause only while a cap applies (X5): the one stored on the version the case would judge.
  const notice =
    version.capPercent === undefined
      ? t('screen.split.resolution.n31NoCap')
      : t('screen.split.resolution.n31', { cap: version.capPercent })
  return (
    <div className="flex flex-col gap-2">
      <Button type="button" variant="secondary" className="w-fit" onClick={() => setOpen(true)}>
        {t('screen.split.resolution.ask')}
      </Button>
      <Dialog.Root
        open={open}
        onOpenChange={(next) => {
          if (!next) {
            setOpen(false)
          }
        }}
      >
        <Dialog.Portal>
          <Dialog.Backdrop className="fixed inset-0 z-[60] bg-foreground/50" />
          <Dialog.Popup className="fixed top-16 left-1/2 z-[70] flex max-h-[calc(100vh-96px)] w-[min(560px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 overflow-y-auto rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
            <Dialog.Title className="text-h2 break-words">
              {t('screen.split.resolution.dialogTitle', { n: card.milestoneNumber })}
            </Dialog.Title>
            <Dialog.Description className="text-body-sm break-words">{notice}</Dialog.Description>
            <div className="flex flex-col gap-1">
              <label htmlFor={`${ids}-reason`} className="text-label">
                {t('screen.split.resolution.reason')}
              </label>
              <textarea
                id={`${ids}-reason`}
                value={reason}
                maxLength={MOST}
                aria-describedby={`${ids}-hint`}
                onChange={(event) => setReason(event.target.value)}
                className={cn(inputClass, 'min-h-24')}
              />
              <p id={`${ids}-hint`} className="text-caption text-muted-foreground">
                {t('screen.split.resolution.reasonHint')}
              </p>
            </div>
            {raise.isError && !stale && (
              <p role="alert" className="text-body-sm text-destructive">
                {refusal(raise.error)}
              </p>
            )}
            <div className="flex flex-wrap justify-between gap-3">
              <Dialog.Close render={<Button type="button" variant="secondary" />}>
                {t('screen.split.resolution.back')}
              </Dialog.Close>
              <Button
                type="button"
                disabled={reason.trim() === '' || raise.isPending}
                onClick={() =>
                  raise.mutate(
                    { splitVersionNo: version.versionNo, reason: reason.trim() },
                    {
                      onSuccess: () => setOpen(false),
                      onError: (error) => {
                        if (isStale(error)) {
                          setOpen(false)
                          setStale(true)
                        }
                      },
                    },
                  )
                }
              >
                {t('screen.split.resolution.send')}
              </Button>
            </div>
          </Dialog.Popup>
        </Dialog.Portal>
      </Dialog.Root>
      <ConfirmDialog
        open={stale}
        title={t('error.STALE')}
        body={t('screen.split.staleBody')}
        confirmLabel={t('screen.split.staleReload')}
        pending={false}
        onConfirm={() => {
          setStale(false)
          raise.reset()
        }}
        onClose={() => {
          setStale(false)
          raise.reset()
        }}
      />
    </div>
  )
}

/** One case: who asked and why, what each party said, and what the reader may still do on it. */
function ResolutionCase({
  jobId,
  card,
  resolution,
  latest,
}: {
  jobId: string
  card: SplitCard
  resolution: SplitResolution
  latest: boolean
}) {
  const heading = resolution.viewerIsRaiser
    ? t('screen.split.resolution.mine')
    : t('screen.split.resolution.case', { name: resolution.raiserName ?? t('screen.split.resolution.unnamed') })
  return (
    <section className="flex flex-col gap-2 rounded-md border border-border p-3 text-body-sm">
      <h3 className="text-label break-words">{heading}</h3>
      <p className="text-caption text-muted-foreground">
        {t('screen.split.resolution.raisedAt', { time: formatDayMonthTime(resolution.raisedAt) })}
      </p>
      <div className="flex flex-col gap-0.5">
        <span className="text-caption text-muted-foreground">{t('screen.split.resolution.reasonLabel')}</span>
        <p className="break-words whitespace-pre-line">{resolution.reason}</p>
      </div>
      {resolution.statements.length > 0 && (
        <div className="flex flex-col gap-1">
          <span className="text-caption text-muted-foreground">{t('screen.split.resolution.statements')}</span>
          <ul className="flex flex-col gap-1">
            {resolution.statements.map((statement) => (
              <li key={statement.partyId} className="rounded-md bg-muted p-2">
                <p className="text-caption text-muted-foreground break-words">
                  {statement.name ?? t('screen.split.resolution.unnamed')}
                  {statement.mentor ? ` · ${t('screen.split.resolution.mentorMark')}` : ''}
                  {' · '}
                  {formatDayMonthTime(statement.submittedAt)}
                </p>
                <p className="break-words whitespace-pre-line">{statement.text}</p>
              </li>
            ))}
          </ul>
        </div>
      )}
      <Ending card={card} resolution={resolution} latest={latest} />
      {resolution.viewerMayState && <StatementForm jobId={jobId} card={card} resolution={resolution} />}
      {resolution.viewerIsRaiser && resolution.status === 'OPEN' && (
        <WithdrawButton jobId={jobId} card={card} resolution={resolution} />
      )}
    </section>
  )
}

/** How a case ended: the new round or the final split a withdrawal left, or the Administrator's reasoning. */
function Ending({ card, resolution, latest }: { card: SplitCard; resolution: SplitResolution; latest: boolean }) {
  if (resolution.status === 'DECIDED') {
    return (
      <div className="flex flex-col gap-0.5">
        <p className="text-caption text-muted-foreground">
          {t('screen.split.resolution.status.DECIDED', { time: formatDayMonthTime(resolution.endedAt ?? '') })}
        </p>
        <span className="text-caption text-muted-foreground">{t('screen.split.resolution.reasoning')}</span>
        <p className="break-words whitespace-pre-line">{resolution.reasoning}</p>
      </div>
    )
  }
  if (resolution.status !== 'WITHDRAWN') {
    return null
  }
  if (latest && card.status === 'PROPOSED' && card.confirmationDueAt) {
    return (
      <p className="text-body-sm">
        {t('screen.split.resolution.withdrawn', { date: formatDayMonth(card.confirmationDueAt) })}
      </p>
    )
  }
  if (latest && card.status === 'FINAL' && card.finality === 'CONFIRMED') {
    return <p className="text-body-sm">{t('screen.split.resolution.withdrawnFinal')}</p>
  }
  return (
    <p className="text-caption text-muted-foreground">
      {t('screen.split.resolution.status.WITHDRAWN', { time: formatDate(resolution.endedAt ?? '') })}
    </p>
  )
}

/** One statement of a party other than the raiser, which cannot be changed, so it asks first. */
function StatementForm({ jobId, card, resolution }: { jobId: string; card: SplitCard; resolution: SplitResolution }) {
  const ids = useId()
  const send = useResolutionStatement(jobId, card.splitId, resolution.id)
  const [text, setText] = useState('')
  const [asking, setAsking] = useState(false)
  return (
    <form
      className="flex flex-col gap-1"
      onSubmit={(event) => {
        event.preventDefault()
        if (text.trim() !== '') {
          setAsking(true)
        }
      }}
    >
      <label htmlFor={`${ids}-text`} className="text-label">
        {t('screen.split.resolution.statementLabel')}
      </label>
      <textarea
        id={`${ids}-text`}
        value={text}
        maxLength={MOST}
        aria-describedby={`${ids}-hint`}
        onChange={(event) => setText(event.target.value)}
        className={cn(inputClass, 'min-h-20')}
      />
      <p id={`${ids}-hint`} className="text-caption text-muted-foreground">
        {t('screen.split.resolution.statementHint')}
      </p>
      {send.isError && (
        <p role="alert" className="text-body-sm text-destructive">
          {refusal(send.error)}
        </p>
      )}
      <Button type="submit" size="compact" className="w-fit" disabled={text.trim() === '' || send.isPending}>
        {t('screen.split.resolution.statementSend')}
      </Button>
      <ConfirmDialog
        open={asking}
        title={t('screen.split.resolution.statementDialogTitle')}
        body={t('screen.split.resolution.statementDialogBody')}
        confirmLabel={t('screen.split.resolution.statementSend')}
        pending={send.isPending}
        onConfirm={() => send.mutate(text.trim(), { onSettled: () => setAsking(false) })}
        onClose={() => setAsking(false)}
      />
    </form>
  )
}

/** "Rút yêu cầu": the deadline restarts, or the split is final when everyone had confirmed; the raise stays used. */
function WithdrawButton({ jobId, card, resolution }: { jobId: string; card: SplitCard; resolution: SplitResolution }) {
  const withdraw = useWithdrawResolution(jobId, card.splitId, resolution.id)
  const [asking, setAsking] = useState(false)
  return (
    <div className="flex flex-col gap-1">
      <Button type="button" variant="secondary" size="compact" className="w-fit" onClick={() => setAsking(true)}>
        {t('screen.split.resolution.withdraw')}
      </Button>
      {withdraw.isError && (
        <p role="alert" className="text-body-sm text-destructive">
          {refusal(withdraw.error)}
        </p>
      )}
      <ConfirmDialog
        open={asking}
        title={t('screen.split.resolution.withdrawDialogTitle')}
        body={t('screen.split.resolution.withdrawDialogBody')}
        confirmLabel={t('screen.split.resolution.withdraw')}
        pending={withdraw.isPending}
        onConfirm={() => withdraw.mutate(undefined, { onSettled: () => setAsking(false) })}
        onClose={() => setAsking(false)}
      />
    </div>
  )
}
