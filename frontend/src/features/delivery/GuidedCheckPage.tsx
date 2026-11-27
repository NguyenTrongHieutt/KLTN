import { Dialog } from '@base-ui/react/dialog'
import { useEffect, useId, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { usePageTitle } from '@/app/usePageTitle'
import { FormField } from '@/components/FormField'
import { fieldProps, inputClass } from '@/components/formField-props'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { TestTag } from '@/components/TestTag'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { buttonVariants } from '@/components/ui/button-variants'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { rich } from '@/i18n/rich'
import { t } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatCalendarDayMonth } from '@/lib/dates'
import { formatVnd } from '@/lib/money'
import { isNotFound, problemMessage } from '@/lib/problemMessage'
import { useDecideHandover, useHandover, useReturnHandover, type DeliveryReport } from './deliveryApi'
import { HandoverLinkList } from './HandoverLinkList'
import { OutsideLink } from './OutsideLink'
import { Page } from '@/components/ui/page'
import { DialogBackdrop, DialogPopup } from '@/components/ui/dialog'

interface Mark {
  met?: boolean
  comment: string
}

interface Draft {
  step: number
  marks: Record<string, Mark>
}

const draftKey = (handoverId: string) => `vibefix.check.${handoverId}`

/** The local draft of the check (UX-DR33): kept per handover in `sessionStorage`, never sent until the decision. */
function readDraft(handoverId: string): Draft {
  try {
    const stored = window.sessionStorage.getItem(draftKey(handoverId))
    if (stored) {
      const parsed = JSON.parse(stored) as Draft
      if (typeof parsed.step === 'number' && parsed.marks && typeof parsed.marks === 'object') {
        return parsed
      }
    }
  } catch {
    // A blocked or full storage only loses the draft.
  }
  return { step: 0, marks: {} }
}

function writeDraft(handoverId: string, draft: Draft | undefined) {
  try {
    if (draft) {
      window.sessionStorage.setItem(draftKey(handoverId), JSON.stringify(draft))
    } else {
      window.sessionStorage.removeItem(draftKey(handoverId))
    }
  } catch {
    // As above.
  }
}

/** `delivery`'s own refusals of a decision on a handover that moved on, each with its sentence under `reason.delivery`. */
const MOVED_ON = ['HANDOVER_VOIDED', 'HANDOVER_RETURNED', 'HANDOVER_DECIDED'] as const

/**
 * Why a decision was refused, in the reader's words. A handover that was voided, returned or decided meanwhile says
 * which of the three it was: a return is not a change on GitHub, and an Assessment Job has no GitHub at all. A Job
 * that moved on says to open the report again. Everything else is read from `module`, whose reasons the command's
 * own guards use.
 */
function refusal(failure: unknown, module: 'delivery' | 'job', operation?: string): string {
  if (failure instanceof ApiError) {
    const movedOn = MOVED_ON.find((reason) => reason === failure.problem.reason)
    if (movedOn) {
      return t(`reason.delivery.${movedOn}`)
    }
    if (failure.problem.code === 'STALE') {
      return t('screen.handover.check.stale')
    }
  }
  return problemMessage(failure, { module, operation }).text
}

/** "Cách kiểm tra" as numbered steps: one per line, or split at "1. … 2. …" written on one line. */
function steps(checkSteps: string): string[] {
  const lines = checkSteps.split(/\n+/).map((line) => line.trim()).filter(Boolean)
  const parts = lines.length > 1 ? lines : checkSteps.split(/(?=\b\d+\.\s)/).map((part) => part.trim()).filter(Boolean)
  return parts.map((part) => part.replace(/^\d+\.\s*/, ''))
}

/**
 * JOB-5's guided check (FR-33, FR-34, UX-DR33), a focused route for the Customer: one criterion per
 * step with "Tiêu chí k/n", the criterion, its numbered "Cách kiểm tra", and the Mentor's note as a
 * quote; "Đạt" or "Chưa đạt" before "Tiếp", "Chưa đạt" with an optional comment. The summary accepts,
 * or asks for a revision of the criteria marked "Chưa đạt" while a Revision Round is left; each asks for
 * confirmation. Where the handover holds the Mentor's N-33 confirmation, the acceptance's Confirmation dialog
 * quotes it with their name: vibefix scans no team code, and the Mentor confirmed this handover holds none (FR-33,
 * R28, Story 23.8b). Nothing is sent before that.
 *
 * In a Self-mentored Job a Crew member made the handover, so the note and N-33's line are the member's, and the
 * summary has a third action for the Customer, "Trả lại bàn giao" with "còn {n} lần trả lại" (FR-86, UX-DR127,
 * Story 25.5): shown whether or not a Revision Round is left, disabled in place with its reason once the returns are
 * used up (R65), and never shown in a normal Job, whose decision block names no return.
 */
export function GuidedCheckPage() {
  const { jobId, handoverId } = useParams()
  const report = useHandover(handoverId)
  usePageTitle(t('screen.handover.check.title'))
  if (!jobId || !handoverId || (report.isError && isNotFound(report.error))) {
    return <NotFoundScreen />
  }
  return (
    <Page width="focused">
      <RegionState query={report} skeleton={<Skeleton className="h-96 w-full" />}>
        {report.data &&
          (report.data.decision ? (
            <Check jobId={jobId} report={report.data} />
          ) : (
            <p className="text-body-sm">
              {t('reason.delivery.HANDOVER_DECIDED')}{' '}
              <Link to={`/cong-viec/${jobId}/ban-giao/${handoverId}`} className="text-primary underline-offset-4 hover:underline">
                {t('screen.handover.report.criteria')}
              </Link>
            </p>
          ))}
      </RegionState>
    </Page>
  )
}

function Check({ jobId, report }: { jobId: string; report: DeliveryReport }) {
  const ids = useId()
  const toasts = useToasts()
  const navigate = useNavigate()
  const decide = useDecideHandover(jobId, report.id)
  const [draft, setDraft] = useState<Draft>(() => readDraft(report.id))
  const [hint, setHint] = useState<string>()
  const [confirming, setConfirming] = useState<'accept' | 'revision'>()
  const [returning, setReturning] = useState(false)
  const [error, setError] = useState<string>()
  const total = report.criteria.length
  const step = Math.min(draft.step, total)
  const decision = report.decision!

  useEffect(() => writeDraft(report.id, draft), [report.id, draft])

  const mark = (criterionId: string, next: Partial<Mark>) =>
    setDraft({
      ...draft,
      marks: {
        ...draft.marks,
        [criterionId]: {
          met: next.met ?? draft.marks[criterionId]?.met,
          comment: next.comment ?? draft.marks[criterionId]?.comment ?? '',
        },
      },
    })
  const notMet = report.criteria.filter((criterion) => draft.marks[criterion.criterionId]?.met === false)

  // D5 (Story 24.4b): the comments go as typed, and nothing checks or masks them.
  const send = (action: 'accept' | 'revision') => {
    setError(undefined)
    decide.mutate(
      {
        action,
        body: {
          version: report.version,
          checks: report.criteria.map((criterion) => ({
            criterionId: criterion.criterionId,
            met: draft.marks[criterion.criterionId]?.met ?? true,
            comment: draft.marks[criterion.criterionId]?.comment.trim() || undefined,
          })),
        },
      },
      {
        onSuccess: (decided) => {
          writeDraft(report.id, undefined)
          toasts.success(
            t(
              decided.acceptance === 'MERGING'
                ? 'screen.handover.acceptance.merging'
                : action === 'accept'
                  ? 'screen.handover.check.accepted'
                  : 'screen.handover.check.revised',
              { n: report.number },
            ),
          )
          void navigate(`/cong-viec/${jobId}/ban-giao/${report.id}`)
        },
        onError: (failure) => {
          setConfirming(undefined)
          setError(refusal(failure, 'delivery'))
        },
      },
    )
  }

  if (step < total) {
    const criterion = report.criteria[step]
    const current = draft.marks[criterion.criterionId]
    return (
      <section aria-labelledby={`${ids}-criterion`} className="flex flex-col gap-4">
        <p className="text-label text-muted-foreground">{t('screen.handover.check.step', { k: step + 1, n: total })}</p>
        <h1 id={`${ids}-criterion`} className="text-h1 break-words">
          {criterion.statement}
        </h1>
        <div className="flex flex-col gap-1 rounded-md bg-muted p-3 text-body-sm">
          <h2 className="text-label">{t('screen.handover.check.howTo')}</h2>
          <ol className="ml-5 list-decimal">
            {steps(criterion.checkSteps).map((line, index) => (
              <li key={index} className="break-words">
                {line}
              </li>
            ))}
          </ol>
          {/*
            UX-DR33: the preview next to each step's "Cách kiểm tra", with N-11, or why there is none -- the recording
            among the links then shows the change instead -- and every link of the handover with its kind, label and
            domain, an Assessment Job's report among them (Story 24.3).
          */}
          {report.preview.preview && (
            <OutsideLink
              url={report.preview.preview.url}
              domain={report.preview.preview.domain}
              label={t('screen.handover.preview.open')}
            />
          )}
          {report.preview.noPreviewReason && (
            <p className="flex flex-col gap-0.5">
              <span className="text-label">{t('screen.handover.preview.reasonLabel')}</span>
              <span className="break-words whitespace-pre-wrap">{report.preview.noPreviewReason}</span>
            </p>
          )}
          <HandoverLinkList links={report.links} />
        </div>
        <figure className="flex flex-col gap-1 border-l-4 border-border pl-3 text-body-sm">
          <figcaption className="text-caption text-muted-foreground">
            {t(report.byMember ? 'screen.handover.check.memberNote' : 'screen.handover.check.mentorNote')}
          </figcaption>
          <blockquote className="break-words whitespace-pre-wrap">{criterion.note}</blockquote>
        </figure>
        <fieldset className="flex flex-wrap gap-4 text-body">
          <legend className="sr-only">{criterion.statement}</legend>
          <label className="flex min-h-tap-min md:min-h-control-compact items-center gap-2">
            <input
              type="radio"
              name={`${ids}-mark`}
              checked={current?.met === true}
              onChange={() => mark(criterion.criterionId, { met: true })}
              className="size-4"
            />
            {t('screen.handover.check.met')}
          </label>
          <label className="flex min-h-tap-min md:min-h-control-compact items-center gap-2">
            <input
              type="radio"
              name={`${ids}-mark`}
              checked={current?.met === false}
              onChange={() => mark(criterion.criterionId, { met: false })}
              className="size-4"
            />
            {t('screen.handover.check.notMet')}
          </label>
        </fieldset>
        {current?.met === false && (
          <div className="flex flex-col gap-1">
            <label htmlFor={`${ids}-comment`} className="text-label">
              {t('screen.handover.check.comment')}
            </label>
            <textarea
              id={`${ids}-comment`}
              value={current.comment}
              maxLength={2000}
              rows={3}
              onChange={(event) => mark(criterion.criterionId, { comment: event.target.value })}
              className={inputClass}
            />
          </div>
        )}
        {hint && (
          <p role="alert" className="text-body-sm text-destructive">
            {hint}
          </p>
        )}
        <div className="flex justify-between gap-3">
          {step > 0 ? (
            <Button type="button" variant="secondary" onClick={() => setDraft({ ...draft, step: step - 1 })}>
              {t('screen.handover.check.previous')}
            </Button>
          ) : (
            <span />
          )}
          <Button
            type="button"
            onClick={() => {
              if (current?.met === undefined) {
                setHint(t('screen.handover.check.pickFirst'))
                return
              }
              setHint(undefined)
              setDraft({ ...draft, step: step + 1 })
            }}
          >
            {t('screen.handover.check.next')}
          </Button>
        </div>
      </section>
    )
  }

  const revise = notMet.length > 0 && decision.revisionAllowed
  return (
    <section aria-labelledby={`${ids}-summary`} className="flex flex-col gap-4">
      <h1 id={`${ids}-summary`} className="text-h1">
        {t('screen.handover.check.summary')}
      </h1>
      <p className="text-body-sm text-muted-foreground">{t('screen.handover.check.summaryLead', { n: total })}</p>
      <ol className="flex flex-col gap-2">
        {report.criteria.map((criterion, index) => {
          const current = draft.marks[criterion.criterionId]
          return (
            <li key={criterion.criterionId} className="flex items-start justify-between gap-3 rounded-md border border-border p-3 text-body-sm">
              <span className="flex min-w-0 flex-col gap-0.5">
                <span className="font-semibold break-words">{criterion.statement}</span>
                <span>{t(current?.met === false ? 'screen.handover.check.notMet' : 'screen.handover.check.met')}</span>
                {current?.met === false && current.comment.trim() && (
                  <span className="break-words whitespace-pre-wrap text-muted-foreground">{current.comment}</span>
                )}
              </span>
              <Button type="button" variant="ghost" size="compact" onClick={() => setDraft({ ...draft, step: index })}>
                {t('screen.handover.check.edit')}
              </Button>
            </li>
          )
        })}
      </ol>
      {notMet.length > 0 && !decision.revisionAllowed && (
        <p className="text-body-sm text-muted-foreground">
          {t(decision.returnsLeft ? 'screen.handover.check.noRoundsReturn' : 'screen.handover.check.noRounds')}
        </p>
      )}
      {error && (
        <p role="alert" className="text-body-sm text-destructive">
          {error}
        </p>
      )}
      <div className="flex flex-wrap items-start justify-end gap-3">
        {decision.returnLimit != null && (
          <span className="flex flex-col items-end gap-1">
            <Button
              type="button"
              variant="secondary"
              disabled={!decision.returnsLeft}
              // R65: disabled in place, and still reachable by keyboard so that its reason is announced.
              focusableWhenDisabled
              aria-describedby={`${ids}-returns`}
              onClick={() => setReturning(true)}
            >
              {t('screen.handover.check.return')}
            </Button>
            <span id={`${ids}-returns`} className="text-caption text-muted-foreground">
              {decision.returnsLeft
                ? t('screen.handover.check.returnsLeft', { n: decision.returnsLeft })
                : t('screen.handover.check.returnsUsed')}
            </span>
          </span>
        )}
        {revise ? (
          <>
            <Button type="button" variant="secondary" onClick={() => setConfirming('accept')}>
              {t('screen.handover.check.accept')}
            </Button>
            <Button type="button" onClick={() => setConfirming('revision')}>
              {t('screen.handover.check.revise', { n: notMet.length })}
            </Button>
          </>
        ) : (
          <>
            {!decision.revisionAllowed && (
              <>
                <Link to={`/cong-viec/${jobId}#tranh-chap`} className={buttonVariants({ variant: 'secondary' })}>
                  {t('screen.handover.check.dispute')}
                </Link>
                <Link
                  to={`/cong-viec/${jobId}/thoa-thuan#doi-han`}
                  className={buttonVariants({ variant: 'secondary' })}
                >
                  {t('screen.handover.check.changeRequest')}
                </Link>
              </>
            )}
            <Button type="button" onClick={() => setConfirming('accept')}>
              {t('screen.handover.check.accept')}
            </Button>
          </>
        )}
      </div>
      <Dialog.Root open={confirming !== undefined} onOpenChange={(open) => !open && setConfirming(undefined)}>
        <Dialog.Portal>
          <DialogBackdrop className="z-[60]" />
          <DialogPopup size="sm" className="z-[70]">
            <Dialog.Title className="text-h2">
              {confirming === 'revision'
                ? t('screen.handover.check.reviseTitle', { n: notMet.length })
                : t('screen.handover.check.acceptTitle', { n: report.number })}
            </Dialog.Title>
            <Dialog.Description className="text-body-sm">
              {confirming === 'revision' ? (
                <>
                  {t(report.byMember ? 'screen.handover.check.reviseBodySelfMentored' : 'screen.handover.check.reviseBody', {
                    r: Math.max(0, report.roundsLeft - 1),
                  })}
                  {/* FR-87: what the request does to the due date, said before the Customer confirms. The server computes it. */}
                  {decision.reworkDueOn && (
                    <span className="mt-2 block font-semibold tabular-nums">
                      {t('screen.handover.check.reviseDue', { date: formatCalendarDayMonth(decision.reworkDueOn) })}
                    </span>
                  )}
                </>
              ) : (
                <>
                  {/* The tag stands beside the amount it marks, not after the sentence's last word. */}
                  {rich(report.byMember ? 'screen.handover.check.acceptBodySelfMentored' : 'screen.handover.check.acceptBody', {
                    amount: (
                      <span className="whitespace-nowrap">
                        {formatVnd(decision.releaseVnd)} <TestTag />
                      </span>
                    ),
                  })}
                  {/*
                    N-33 (FR-33, R28, Story 23.8b): shown before the Customer accepts, never acknowledged, and only
                    where the handover really holds the Mentor's confirmation.
                  */}
                  {report.code?.teamCodeConfirmedAt && (
                    <span className="mt-2 block break-words">
                      {t(report.byMember ? 'notice.teamCode.customerSelfMentored' : 'notice.teamCode.customer', {
                        mentor: report.mentorName || t('screen.split.erased'),
                      })}
                    </span>
                  )}
                </>
              )}
            </Dialog.Description>
            <div className="flex flex-wrap justify-end gap-3">
              <Button type="button" variant="secondary" onClick={() => setConfirming(undefined)}>
                {t('screen.handover.check.cancel')}
              </Button>
              <Button type="button" disabled={decide.isPending} onClick={() => confirming && send(confirming)}>
                {t('screen.handover.check.confirm')}
              </Button>
            </div>
          </DialogPopup>
        </Dialog.Portal>
      </Dialog.Root>
      {returning && (
        <ReturnDialog
          jobId={jobId}
          report={report}
          onClose={() => setReturning(false)}
          onReturned={() => writeDraft(report.id, undefined)}
        />
      )}
    </section>
  )
}

/**
 * "Trả lại bàn giao Giai đoạn {n}?" (FR-86, FR-87, UX-DR127, UX-DR128): a Confirmation dialog with the reason the
 * members will read as written, what the return does, that it uses no Revision Round, how many returns are left
 * after this one, and the Milestone's new due date when the server names one. A busy Job, a Dispute's freeze and a
 * handover that changed meanwhile answer with their own sentence, and nothing was returned.
 */
function ReturnDialog({
  jobId,
  report,
  onClose,
  onReturned,
}: {
  jobId: string
  report: DeliveryReport
  onClose: () => void
  onReturned: () => void
}) {
  const ids = useId()
  const toasts = useToasts()
  const navigate = useNavigate()
  const returnHandover = useReturnHandover(jobId, report.id)
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string>()
  const decision = report.decision
  const left = decision?.returnsLeft ?? 0
  const confirm = () => {
    setError(undefined)
    returnHandover.mutate(
      { version: report.version, reason: reason.trim() },
      {
        onSuccess: () => {
          onReturned()
          toasts.success(
            decision?.reworkDueOn
              ? t('screen.handover.check.returnedDue', { n: report.number, date: formatCalendarDayMonth(decision.reworkDueOn) })
              : t('screen.handover.check.returned', { n: report.number }),
          )
          void navigate(`/cong-viec/${jobId}/ban-giao/${report.id}`)
        },
        // The return's own guards are `job`'s (the limit, the freeze); a handover that moved on names itself.
        onError: (failure) => setError(refusal(failure, 'job', t('screen.handover.check.busyOperation'))),
      },
    )
  }
  return (
    <Dialog.Root open onOpenChange={(open) => !open && onClose()}>
      <Dialog.Portal>
        <DialogBackdrop className="z-[60]" />
        <DialogPopup size="sm" className="z-[70]">
          <Dialog.Title className="text-h2">{t('screen.handover.check.returnTitle', { n: report.number })}</Dialog.Title>
          <Dialog.Description className="flex flex-col gap-2 text-body-sm">
            <span>{t('screen.handover.check.returnWhat')}</span>
            {report.code && <span>{t('screen.handover.check.returnWhatCode')}</span>}
            <span>{t('screen.handover.check.returnNoRound')}</span>
            <span className="tabular-nums">
              {t('screen.handover.check.returnCount', { n: left, m: Math.max(0, left - 1) })}
            </span>
            {/* FR-87: the due date the return gives the Milestone, from the server; nothing when it moves none. */}
            {decision?.reworkDueOn && (
              <span className="font-semibold tabular-nums">
                {t('screen.handover.check.reviseDue', { date: formatCalendarDayMonth(decision.reworkDueOn) })}
              </span>
            )}
          </Dialog.Description>
          <FormField
            id={`${ids}-reason`}
            label={t('screen.handover.check.returnReason')}
            helper={t('screen.handover.check.returnReasonHelper')}
          >
            <textarea
              {...fieldProps(`${ids}-reason`, undefined, t('screen.handover.check.returnReasonHelper'))}
              required
              rows={3}
              maxLength={2000}
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              className={inputClass}
            />
          </FormField>
          {error && (
            <p role="alert" className="text-body-sm text-destructive">
              {error}
            </p>
          )}
          <div className="flex flex-wrap justify-end gap-3">
            <Dialog.Close render={<Button variant="secondary" />}>{t('screen.handover.check.cancel')}</Dialog.Close>
            <Button type="button" disabled={reason.trim() === '' || returnHandover.isPending} onClick={confirm}>
              {t('screen.handover.check.return')}
            </Button>
          </div>
        </DialogPopup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
