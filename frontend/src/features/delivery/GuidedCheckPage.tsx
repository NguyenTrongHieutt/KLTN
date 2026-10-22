import { Dialog } from '@base-ui/react/dialog'
import { useEffect, useId, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { usePageTitle } from '@/app/usePageTitle'
import { inputClass } from '@/components/formField-props'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { TestTag } from '@/components/TestTag'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { buttonVariants } from '@/components/ui/button-variants'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { SensitiveDialog } from '@/features/work/SensitiveDialog'
import type { SensitiveMatch } from '@/features/work/workApi'
import { t } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatVnd } from '@/lib/money'
import { isNotFound, problemMessage } from '@/lib/problemMessage'
import { useDecideHandover, useHandover, type DeliveryReport } from './deliveryApi'
import { OutsideLink } from './OutsideLink'

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
 * confirmation. Nothing is sent before that.
 */
export function GuidedCheckPage() {
  const { jobId, handoverId } = useParams()
  const report = useHandover(handoverId)
  usePageTitle(t('screen.handover.check.title'))
  if (!jobId || !handoverId || (report.isError && isNotFound(report.error))) {
    return <NotFoundScreen />
  }
  return (
    <div className="mx-auto flex w-full max-w-[720px] flex-col gap-5 px-gutter-mobile py-section-gap md:px-gutter-desktop">
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
    </div>
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
  const [error, setError] = useState<string>()
  const [matches, setMatches] = useState<SensitiveMatch[]>()
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

  const send = (action: 'accept' | 'revision', sensitive: 'CHECK' | 'MASK') => {
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
          sensitive,
        },
      },
      {
        onSuccess: () => {
          writeDraft(report.id, undefined)
          setMatches(undefined)
          toasts.success(t(action === 'accept' ? 'screen.handover.check.accepted' : 'screen.handover.check.revised', { n: report.number }))
          void navigate(`/cong-viec/${jobId}/ban-giao/${report.id}`)
        },
        onError: (failure) => {
          setConfirming(undefined)
          if (failure instanceof ApiError && failure.problem.reason === 'SENSITIVE_CONTENT') {
            setMatches((failure.problem as { matches?: SensitiveMatch[] }).matches ?? [])
            return
          }
          setMatches(undefined)
          setError(
            failure instanceof ApiError && failure.problem.code === 'STALE'
              ? t('screen.handover.check.stale')
              : problemMessage(failure, { module: 'delivery' }).text,
          )
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
        <div className="flex flex-col gap-1 rounded-md bg-muted p-4 text-body-sm">
          <h2 className="text-label">{t('screen.handover.check.howTo')}</h2>
          <ol className="ml-5 list-decimal">
            {steps(criterion.checkSteps).map((line, index) => (
              <li key={index} className="break-words">
                {line}
              </li>
            ))}
          </ol>
          {/* UX-DR33: the preview next to each step's "Cách kiểm tra", with N-11. */}
          {report.preview.preview && (
            <OutsideLink
              url={report.preview.preview.url}
              domain={report.preview.preview.domain}
              label={t('screen.handover.preview.open')}
            />
          )}
          {report.preview.recording && (
            <OutsideLink
              url={report.preview.recording.url}
              domain={report.preview.recording.domain}
              label={t('screen.handover.preview.openRecording')}
            />
          )}
        </div>
        <figure className="flex flex-col gap-1 border-l-4 border-border pl-3 text-body-sm">
          <figcaption className="text-caption text-muted-foreground">{t('screen.handover.check.mentorNote')}</figcaption>
          <blockquote className="break-words whitespace-pre-wrap">{criterion.note}</blockquote>
        </figure>
        <fieldset className="flex flex-wrap gap-4 text-body">
          <legend className="sr-only">{criterion.statement}</legend>
          <label className="flex min-h-tap-min items-center gap-2">
            <input
              type="radio"
              name={`${ids}-mark`}
              checked={current?.met === true}
              onChange={() => mark(criterion.criterionId, { met: true })}
              className="size-4"
            />
            {t('screen.handover.check.met')}
          </label>
          <label className="flex min-h-tap-min items-center gap-2">
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
        <p className="text-body-sm text-muted-foreground">{t('screen.handover.check.noRounds')}</p>
      )}
      {error && (
        <p role="alert" className="text-body-sm text-destructive">
          {error}
        </p>
      )}
      <div className="flex flex-wrap justify-end gap-3">
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
          <Dialog.Backdrop className="fixed inset-0 z-[60] bg-foreground/50" />
          <Dialog.Popup className="fixed top-24 left-1/2 z-[70] flex w-[min(480px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
            <Dialog.Title className="text-h2">
              {confirming === 'revision'
                ? t('screen.handover.check.reviseTitle', { n: notMet.length })
                : t('screen.handover.check.acceptTitle', { n: report.number })}
            </Dialog.Title>
            <Dialog.Description className="text-body-sm">
              {confirming === 'revision' ? (
                t('screen.handover.check.reviseBody', { r: Math.max(0, report.roundsLeft - 1) })
              ) : (
                <>
                  {t('screen.handover.check.acceptBody', { amount: formatVnd(decision.releaseVnd) })} <TestTag />
                </>
              )}
            </Dialog.Description>
            <div className="flex flex-wrap justify-end gap-3">
              <Button type="button" variant="secondary" onClick={() => setConfirming(undefined)}>
                {t('screen.handover.check.cancel')}
              </Button>
              <Button type="button" disabled={decide.isPending} onClick={() => confirming && send(confirming, 'CHECK')}>
                {t('screen.handover.check.confirm')}
              </Button>
            </div>
          </Dialog.Popup>
        </Dialog.Portal>
      </Dialog.Root>
      {matches && (
        <SensitiveDialog
          matches={matches}
          pending={decide.isPending}
          onMask={() => send('revision', 'MASK')}
          onEdit={() => setMatches(undefined)}
        />
      )}
    </section>
  )
}
