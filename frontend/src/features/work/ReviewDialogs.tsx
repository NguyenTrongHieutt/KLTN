import { Dialog } from '@base-ui/react/dialog'
import { Check, X } from 'lucide-react'
import { useId, useState, type ReactNode } from 'react'
import { inputClass } from '@/components/formField-props'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t, type MessageKey } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatDayMonthTime } from '@/lib/dates'
import { problemMessage } from '@/lib/problemMessage'
import { useReviewWorkItem, useSubmitWorkItem } from './moves'
import { useWorkItem, type WorkItemCard } from './workApi'

const SELF_CHECK = ['meetsCriteria', 'tested', 'docsUpdated', 'noSecrets'] as const

type Answers = Record<(typeof SELF_CHECK)[number], boolean>

function refusal(error: unknown): { text: string; sensitive: boolean } {
  const sensitive = error instanceof ApiError && error.problem.reason === 'SENSITIVE_CONTENT'
  return { text: problemMessage(error, { module: 'work' }).text, sensitive }
}

function Frame({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  return (
    <Dialog.Root open onOpenChange={(next) => !next && onClose()}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-[60] bg-foreground/50" />
        <Dialog.Popup className="fixed top-16 left-1/2 z-[70] flex max-h-[calc(100vh-96px)] w-[min(560px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 overflow-y-auto rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
          <Dialog.Title className="text-h2">{title}</Dialog.Title>
          {children}
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}

/**
 * The Submit dialog (FR-25, AD-39, UX-DR38): the four Self-check items, all required, and an optional
 * note for the Mentor; a refusal shows inline. Attachments come with Story 5.8.
 */
export function SubmitDialog({ item, jobId, onClose }: { item: WorkItemCard; jobId: string; onClose: () => void }) {
  const ids = useId()
  const toasts = useToasts()
  const submit = useSubmitWorkItem(jobId)
  const [answers, setAnswers] = useState<Answers>({ meetsCriteria: false, tested: false, docsUpdated: false, noSecrets: false })
  const [note, setNote] = useState('')
  const [error, setError] = useState<{ text: string; sensitive: boolean }>()
  const send = (sensitive: 'CHECK' | 'MASK') => {
    if (!SELF_CHECK.every((key) => answers[key])) {
      setError({ text: t('reason.work.SELF_CHECK_INCOMPLETE'), sensitive: false })
      return
    }
    submit.mutate(
      { itemId: item.id, body: { version: item.version, selfCheck: answers, note, sensitive } },
      {
        onSuccess: () => {
          toasts.success(t('screen.board.submit.sent', { key: item.key }))
          onClose()
        },
        onError: (failure) => setError(refusal(failure)),
      },
    )
  }
  return (
    <Frame title={t('screen.board.submit.title', { key: item.key })} onClose={onClose}>
      <p className="text-body-sm break-words">{item.title}</p>
      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1 text-label">{t('screen.board.submit.selfCheck')}</legend>
        {SELF_CHECK.map((key) => (
          <label key={key} className="flex min-h-tap-min items-center gap-2 text-body-sm">
            <input
              type="checkbox"
              checked={answers[key]}
              onChange={(event) => setAnswers({ ...answers, [key]: event.target.checked })}
              className="size-4"
            />
            {t(`screen.board.submit.${key}` as MessageKey)}
          </label>
        ))}
      </fieldset>
      <label htmlFor={`${ids}-note`} className="text-label">
        {t('screen.board.submit.note')}
      </label>
      <textarea
        id={`${ids}-note`}
        value={note}
        maxLength={4000}
        rows={3}
        onChange={(event) => setNote(event.target.value)}
        className={inputClass}
      />
      {error && (
        <p role="alert" className="text-body-sm text-destructive">
          {error.text}
        </p>
      )}
      <div className="flex flex-wrap justify-end gap-3">
        <Button type="button" variant="secondary" onClick={onClose}>
          {t('screen.board.review.cancel')}
        </Button>
        {error?.sensitive ? (
          <Button type="button" disabled={submit.isPending} onClick={() => send('MASK')}>
            {t('screen.board.submit.mask')}
          </Button>
        ) : (
          <Button type="button" disabled={submit.isPending} onClick={() => send('CHECK')}>
            {t('screen.board.submit.send')}
          </Button>
        )}
      </div>
    </Frame>
  )
}

/**
 * The Review dialog (FR-25, AD-39, UX-DR38): the Self-check answers and the note of the latest
 * submission, a written comment, and "Duyệt" (to "Xong") or "Trả lại" (to "Đang làm"); a refusal shows
 * inline. Attachments and notes come with Story 5.8.
 */
export function ReviewDialog({ item, jobId, onClose }: { item: WorkItemCard; jobId: string; onClose: () => void }) {
  const ids = useId()
  const toasts = useToasts()
  const detail = useWorkItem(item.id)
  const review = useReviewWorkItem(jobId)
  const [comment, setComment] = useState('')
  const [error, setError] = useState<{ text: string; sensitive: boolean }>()
  const [last, setLast] = useState<'APPROVE' | 'RETURN'>('APPROVE')
  const decide = (decision: 'APPROVE' | 'RETURN', sensitive: 'CHECK' | 'MASK' = 'CHECK') => {
    setLast(decision)
    if (comment.trim() === '') {
      setError({ text: t('reason.work.REVIEW_COMMENT_REQUIRED'), sensitive: false })
      return
    }
    review.mutate(
      { itemId: item.id, body: { version: item.version, decision, comment, sensitive } },
      {
        onSuccess: () => {
          toasts.success(t(decision === 'APPROVE' ? 'screen.board.review.approved' : 'screen.board.review.returned', { key: item.key }))
          onClose()
        },
        onError: (failure) => setError(refusal(failure)),
      },
    )
  }
  const submission = detail.data?.review?.action === 'SUBMITTED' ? detail.data.review : undefined
  return (
    <Frame title={t('screen.board.review.title', { key: item.key })} onClose={onClose}>
      <p className="text-body-sm break-words">{item.title}</p>
      <RegionState query={detail} skeleton={<Skeleton className="h-24 w-full" />}>
        {submission && (
          <section aria-labelledby={`${ids}-answers`} className="flex flex-col gap-2 rounded-md bg-muted p-3 text-body-sm">
            <h3 id={`${ids}-answers`} className="text-label">
              {t('screen.board.review.answers', { name: submission.by, time: formatDayMonthTime(submission.at) })}
            </h3>
            <ul className="flex flex-col gap-1">
              {SELF_CHECK.map((key) => {
                const yes = submission.selfCheck?.[key] ?? false
                return (
                  <li key={key} className="flex items-center gap-2">
                    {yes ? (
                      <Check aria-hidden="true" className="size-4 text-success" />
                    ) : (
                      <X aria-hidden="true" className="size-4 text-destructive" />
                    )}
                    <span>{t(`screen.board.submit.${key}` as MessageKey)}</span>
                    <span className="sr-only">{t(yes ? 'screen.board.review.yes' : 'screen.board.review.no')}</span>
                  </li>
                )
              })}
            </ul>
            {submission.comment && (
              <p className="break-words whitespace-pre-wrap">
                {t('screen.board.review.note')}: {submission.comment}
              </p>
            )}
          </section>
        )}
      </RegionState>
      <label htmlFor={`${ids}-comment`} className="text-label">
        {t('screen.board.review.comment')}
      </label>
      <textarea
        id={`${ids}-comment`}
        value={comment}
        maxLength={4000}
        rows={3}
        onChange={(event) => setComment(event.target.value)}
        className={inputClass}
      />
      {error && (
        <p role="alert" className="text-body-sm text-destructive">
          {error.text}
        </p>
      )}
      <div className="flex flex-wrap justify-end gap-3">
        {error?.sensitive ? (
          <Button type="button" disabled={review.isPending} onClick={() => decide(last, 'MASK')}>
            {t('screen.board.item.mask')}
          </Button>
        ) : (
          <>
            <Button type="button" variant="secondary" disabled={review.isPending} onClick={() => decide('RETURN')}>
              {t('screen.board.move.return')}
            </Button>
            <Button type="button" disabled={review.isPending} onClick={() => decide('APPROVE')}>
              {t('screen.board.move.approve')}
            </Button>
          </>
        )}
      </div>
    </Frame>
  )
}
