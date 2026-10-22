import { Dialog } from '@base-ui/react/dialog'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Snowflake } from 'lucide-react'
import { useId, useState } from 'react'
import { inputClass } from '@/components/formField-props'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { NOTIFICATION_QUERY_KEY } from '@/features/notification/api'
import { t, type MessageKey } from '@/i18n/t'
import { apiClient, unwrap } from '@/lib/apiClient'
import { formatDayMonthTime } from '@/lib/dates'
import { formatVnd } from '@/lib/money'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { useJobDispute, type JobDispute } from './disputeApi'


/**
 * The Frozen banner "Đang tranh chấp — tiền và mọi thời hạn tạm dừng" for every party while a Dispute is
 * open (UX-DR22), and "Mở tranh chấp" with the Open-Dispute dialog for the Customer and the Mentor
 * (UX-DR56): the reason, what freezes, what continues, and the other side's statement period. While it is
 * open, the other side sends its one statement with "Gửi trình bày" (FR-52), and both sides read them.
 */
export function DisputeSection({ jobId }: { jobId: string }) {
  const dispute = useJobDispute(jobId)
  // An auxiliary part of the page: nothing while loading or when the read fails.
  return dispute.data && (dispute.data.open || dispute.data.canOpen || dispute.data.decided) ? (
    <Content jobId={jobId} data={dispute.data} />
  ) : null
}

function Content({ jobId, data }: { jobId: string; data: JobDispute }) {
  const ids = useId()
  const toasts = useToasts()
  const queryClient = useQueryClient()
  const [asking, setAsking] = useState(false)
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string>()
  const open = useMutation({
    mutationFn: (text: string) =>
      unwrap(apiClient.POST('/api/v1/boards/{boardId}/disputes', { params: { path: { boardId: jobId } }, body: { reason: text } })),
    onSuccess: async (view) => {
      queryClient.setQueryData(['dispute', jobId], view)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['progress', jobId] }),
        queryClient.invalidateQueries({ queryKey: NOTIFICATION_QUERY_KEY }),
      ])
    },
  })
  if (data.open) {
    return (
      <div
        id="tranh-chap"
        role="status"
        className="flex gap-3 rounded-md border-l-4 border-primary bg-primary-soft p-4 text-body-sm"
      >
        <Snowflake aria-hidden="true" className="size-5 shrink-0 text-primary" />
        <div className="flex min-w-0 grow flex-col gap-1">
          <p className="font-semibold">{t('screen.dispute.banner')}</p>
          <p className="text-muted-foreground">
            {t(`screen.dispute.openedBy.${data.open.openedBy}` as MessageKey, { time: formatDayMonthTime(data.open.openedAt) })}
          </p>
          {data.audience !== 'CREW' && (
            <Statements jobId={jobId} data={data} />
          )}
        </div>
      </div>
    )
  }
  if (data.decided && !data.canOpen) {
    return <Decided decided={data.decided} />
  }
  const send = () => {
    setError(undefined)
    open.mutate(reason.trim(), {
      onSuccess: () => {
        setAsking(false)
        toasts.success(t('screen.dispute.opened'))
      },
      onError: (failure) => setError(problemMessage(failure, { module: 'dispute' }).text),
    })
  }
  return (
    <div id="tranh-chap" className="flex justify-end">
      <Button type="button" variant="secondary" onClick={() => setAsking(true)}>
        {t('screen.dispute.open')}
      </Button>
      <Dialog.Root open={asking} onOpenChange={(next) => !next && setAsking(false)}>
        <Dialog.Portal>
          <Dialog.Backdrop className="fixed inset-0 z-[60] bg-foreground/50" />
          <Dialog.Popup className="fixed top-24 left-1/2 z-[70] flex w-[min(520px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
            <Dialog.Title className="text-h2">{t('screen.dispute.title')}</Dialog.Title>
            <Dialog.Description className="flex flex-col gap-2 text-body-sm">
              <span>{t('screen.dispute.freezes')}</span>
              <span>{t('screen.dispute.continues')}</span>
              <span>{t('screen.dispute.decides')}</span>
              <span>{t('screen.dispute.period')}</span>
            </Dialog.Description>
            <label htmlFor={`${ids}-reason`} className="text-label">
              {t('screen.dispute.reason')}
            </label>
            <textarea
              id={`${ids}-reason`}
              value={reason}
              maxLength={2000}
              onChange={(event) => setReason(event.target.value)}
              className={cn(inputClass, 'min-h-18')}
            />
            {error && (
              <p role="alert" className="text-body-sm text-destructive">
                {error}
              </p>
            )}
            <div className="flex flex-wrap justify-end gap-3">
              <Button type="button" variant="secondary" onClick={() => setAsking(false)}>
                {t('screen.dispute.cancel')}
              </Button>
              <Button type="button" variant="destructive" disabled={reason.trim() === '' || open.isPending} onClick={send}>
                {t('screen.dispute.send')}
              </Button>
            </div>
          </Dialog.Popup>
        </Dialog.Portal>
      </Dialog.Root>
    </div>
  )
}

/** The statements both sides read, and the other side's "Gửi trình bày" while it may still send one. */
function Statements({ jobId, data }: { jobId: string; data: JobDispute }) {
  const ids = useId()
  const toasts = useToasts()
  const queryClient = useQueryClient()
  const [text, setText] = useState('')
  const [error, setError] = useState<string>()
  const state = useMutation({
    mutationFn: (statement: string) =>
      unwrap(
        apiClient.POST('/api/v1/boards/{boardId}/dispute/statement', {
          params: { path: { boardId: jobId } },
          body: { text: statement },
        }),
      ),
    onSuccess: async (view) => {
      queryClient.setQueryData(['dispute', jobId], view)
      await queryClient.invalidateQueries({ queryKey: NOTIFICATION_QUERY_KEY })
    },
  })
  const open = data.open
  if (!open) {
    return null
  }
  const send = () => {
    setError(undefined)
    state.mutate(text.trim(), {
      onSuccess: () => {
        setText('')
        toasts.success(t('screen.dispute.statementSent'))
      },
      onError: (failure) => setError(problemMessage(failure, { module: 'dispute' }).text),
    })
  }
  return (
    <div className="mt-2 flex flex-col gap-2">
      {open.statements.map((statement) => (
        <div key={statement.side} className="rounded-md bg-background p-3">
          <p className="text-caption text-muted-foreground">
            {t(`screen.dispute.statementBy.${statement.side}` as MessageKey, { time: formatDayMonthTime(statement.submittedAt) })}
          </p>
          <p className="break-words whitespace-pre-line">{statement.text}</p>
        </div>
      ))}
      {open.canState && (
        <div className="flex flex-col gap-2">
          <label htmlFor={`${ids}-statement`} className="text-label">
            {t('screen.dispute.statementLabel', { date: formatDayMonthTime(open.statementDueAt) })}
          </label>
          <textarea
            id={`${ids}-statement`}
            value={text}
            maxLength={4000}
            onChange={(event) => setText(event.target.value)}
            className={cn(inputClass, 'min-h-24')}
          />
          {error && (
            <p role="alert" className="text-destructive">
              {error}
            </p>
          )}
          <div>
            <Button type="button" disabled={text.trim() === '' || state.isPending} onClick={send}>
              {t('screen.dispute.statementSend')}
            </Button>
          </div>
        </div>
      )}
    </div>
  )
}

function Decided({ decided }: { decided: NonNullable<JobDispute['decided']> }) {
  return (
    <div id="tranh-chap" role="status" className="flex flex-col gap-1 rounded-md bg-muted p-4 text-body-sm">
      <p className="font-semibold">{t('screen.dispute.decided', { time: formatDayMonthTime(decided.decidedAt) })}</p>
      <p>{t(`screen.dispute.decidedNext.${decided.nextState}` as MessageKey)}</p>
      {decided.releaseVnd != null && decided.refundVnd != null && (
        <p>
          {t('screen.dispute.decidedAmounts', { release: formatVnd(decided.releaseVnd), refund: formatVnd(decided.refundVnd) })}
        </p>
      )}
      <p className="break-words whitespace-pre-line">{t('screen.dispute.reasoning', { reasoning: decided.reasoning })}</p>
    </div>
  )
}
