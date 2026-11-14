import { Check, TriangleAlert } from 'lucide-react'
import { useEffect, useId, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { usePageTitle } from '@/app/usePageTitle'
import { inputClass } from '@/components/formField-props'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { SensitiveDialog } from '@/features/work/SensitiveDialog'
import type { SensitiveMatch } from '@/features/work/workApi'
import { t, type MessageKey } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { isNotFound, problemMessage, reasonMessage, sentence } from '@/lib/problemMessage'
import { CopyRunBanner } from './CopyRunBanner'
import {
  TEAM_CODE_NOTICE_VERSION,
  useHandOver,
  useHandoverPreflight,
  useHandoverSubmission,
  type HandoverPreflight,
  type HandoverSubmission,
} from './deliveryApi'
import { EvidenceFields } from './HandoverEvidence'
import { NO_EVIDENCE, evidenceBody, evidenceReady, type Evidence } from './evidenceRules'
import { SensitiveChangeList } from './SensitiveChangeList'

interface Mark {
  met?: boolean
  note: string
}

/**
 * Why the copy was refused, in the module's own words: the submission names the module its reason belongs to
 * (AD-23), and a reason the catalog has no text for reads as the plain fallback, never as a code.
 */
function refusal(submission: HandoverSubmission): string {
  const own = submission.reason && submission.reasonModule
    ? reasonMessage(submission.reasonModule, submission.reason)
    : undefined
  return own ? sentence(own) : t('screen.handover.form.copyFailed')
}

/**
 * "Tạo bàn giao" (FR-10, FR-32, AD-23, UX-DR54), a focused route for the Mentor: the preflight of the
 * Milestone waiting for its handover. Every Work Item in "Xong" through an approval or a note for it;
 * every Acceptance Criterion "Đạt" or "Chưa đạt" with a note, next to "Cách kiểm tra"; for an Assessment
 * Job, the Report version to hand over; for a Code Job, its new commits and the Sensitive change
 * preview; the preview link, or a reason with a recording, and screenshots (Story 8.3). A Code Job also needs
 * N-33's Mentor checkbox, the duty to keep credentials and real data out of the handover's commits, since vibefix
 * scans no team code (FR-25, R28, Story 23.8b); the request names the version of that text, and a reworded one
 * answers `NOTICE_REQUIRED` and offers a reload. "Gửi bàn giao" enables only when all of it passes, and sends the
 * Job version the form loaded. While the runner copies, the Actions run status row says where the run stands.
 */
export function HandoverFormPage() {
  const { jobId } = useParams()
  const preflight = useHandoverPreflight(jobId)
  usePageTitle(t('screen.handover.form.title'))
  if (!jobId || (preflight.isError && isNotFound(preflight.error))) {
    return <NotFoundScreen />
  }
  return (
    <div className="mx-auto flex w-full max-w-[880px] flex-col gap-5 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <RegionState query={preflight} skeleton={<Skeleton className="h-96 w-full" />}>
        {preflight.data && <Form jobId={jobId} preflight={preflight.data} onStale={() => void preflight.refetch()} />}
      </RegionState>
    </div>
  )
}

function Form({ jobId, preflight, onStale }: { jobId: string; preflight: HandoverPreflight; onStale: () => void }) {
  const ids = useId()
  const toasts = useToasts()
  const navigate = useNavigate()
  const handOver = useHandOver(jobId)
  const [marks, setMarks] = useState<Record<string, Mark>>({})
  const [itemNotes, setItemNotes] = useState<Record<string, string>>({})
  const [error, setError] = useState<string>()
  const [matches, setMatches] = useState<SensitiveMatch[]>()
  // N-33 (FR-25, R28, Story 23.8b): the Mentor's duty, which a Code Job's handover cannot go without.
  const [teamCode, setTeamCode] = useState(false)
  const [noticeChanged, setNoticeChanged] = useState(false)
  // Story 23.8a: a Code Job's handover waits while the runner copies it, so the form reads its submission.
  const [copying, setCopying] = useState<string>()
  const submission = useHandoverSubmission(jobId, copying !== undefined)
  // Only this send's own submission decides: an earlier one the cache still holds is not it.
  const copied = copying !== undefined && submission.data?.id === copying ? submission.data : undefined
  const [evidence, setEvidence] = useState<Evidence>(() => ({
    ...NO_EVIDENCE,
    screenshotIds: preflight.screenshots.slice(0, 5).map((upload) => upload.fileId),
  }))
  const unfinished = preflight.workItems.filter((item) => !item.approved)
  const criteriaReady = preflight.criteria.every((criterion) => {
    const mark = marks[criterion.criterionId]
    return mark?.met !== undefined && mark.note.trim() !== ''
  })
  const itemsReady = unfinished.every((item) => (itemNotes[item.id] ?? '').trim() !== '')
  // An Assessment Job hands over no code, so N-33 is neither shown nor needed there.
  const teamCodeReady = preflight.assessment || teamCode
  const ready =
    preflight.problems.length === 0 &&
    criteriaReady &&
    itemsReady &&
    teamCodeReady &&
    evidenceReady(evidence, preflight.previewRequired)

  // The copy is over when this send's submission is no longer pending; a refusal names its reason inline.
  const waiting = copying !== undefined && !submission.isError && (copied === undefined || copied.state === 'PENDING')
  const notCopied = copied?.state === 'REFUSED' ? t('screen.handover.form.notCopied', { reason: refusal(copied) }) : undefined
  const unread = copying !== undefined && submission.isError ? t('screen.handover.form.notRead') : undefined

  // Told and opened once per submission, however often the read answers while the route changes.
  const opened = useRef<string>(undefined)
  useEffect(() => {
    if (copied?.state !== 'DONE' || !copied.handoverId || opened.current === copied.id) {
      return
    }
    opened.current = copied.id
    toasts.success(t('screen.handover.form.sent', { n: copied.number }))
    void navigate(`/cong-viec/${jobId}/ban-giao/${copied.handoverId}`)
  }, [copied, jobId, navigate, toasts])

  const send = (sensitive: 'CHECK' | 'MASK') => {
    setError(undefined)
    setNoticeChanged(false)
    setCopying(undefined)
    handOver.mutate(
      {
        milestoneId: preflight.milestone.milestoneId,
        version: preflight.version,
        criteria: preflight.criteria.map((criterion) => ({
          criterionId: criterion.criterionId,
          met: marks[criterion.criterionId]?.met ?? false,
          note: marks[criterion.criterionId]?.note.trim() ?? '',
        })),
        itemNotes: unfinished.map((item) => ({ workItemId: item.id, note: (itemNotes[item.id] ?? '').trim() })),
        sensitive,
        teamCodeNoticeVersion: preflight.assessment ? undefined : TEAM_CODE_NOTICE_VERSION,
        ...evidenceBody(evidence),
      },
      {
        onSuccess: (sent) => {
          setMatches(undefined)
          if (sent.state === 'DONE' && sent.handoverId) {
            toasts.success(t('screen.handover.form.sent', { n: sent.number }))
            void navigate(`/cong-viec/${jobId}/ban-giao/${sent.handoverId}`)
            return
          }
          // The read is enabled by this state, so it starts on its own: one fetch per poll, no extra one here.
          setCopying(sent.id)
        },
        onError: (failure) => {
          if (failure instanceof ApiError && failure.problem.reason === 'SENSITIVE_CONTENT') {
            setMatches((failure.problem as { matches?: SensitiveMatch[] }).matches ?? [])
            return
          }
          setMatches(undefined)
          if (failure instanceof ApiError && failure.problem.code === 'STALE') {
            setError(t('screen.handover.form.stale'))
            onStale()
            return
          }
          // N-33 was reworded while the form was open: the new text is read after a reload (Story 23.8b).
          if (failure instanceof ApiError && failure.problem.reason === 'NOTICE_REQUIRED') {
            setNoticeChanged(true)
            return
          }
          setError(problemMessage(failure, { module: 'delivery' }).text)
        },
      },
    )
  }

  return (
    <div className="flex flex-col gap-6">
      <header className="flex flex-col gap-1">
        <h1 className="text-h1 break-words">
          {t('screen.handover.form.heading', { n: preflight.milestone.number, title: preflight.milestone.title })}
        </h1>
        <p className="text-body-sm text-muted-foreground">{t('screen.handover.form.lead')}</p>
      </header>
      {preflight.problems.length > 0 && (
        <ul className="flex flex-col gap-2">
          {preflight.problems.map((problem) => (
            <li key={problem} role="alert" className="flex gap-2 rounded-md border-l-4 border-destructive bg-destructive-soft p-3 text-body-sm">
              <TriangleAlert aria-hidden="true" className="size-5 shrink-0 text-destructive" />
              {t(`reason.delivery.${problem}` as MessageKey)}
            </li>
          ))}
        </ul>
      )}
      {!preflight.assessment && preflight.commitCount > 0 && (
        <div className="flex flex-col gap-2">
          <p className="text-body-sm">{t('screen.handover.code.formCommits', { n: preflight.commitCount })}</p>
          {preflight.sensitiveChanges.length > 0 && (
            <p className="text-caption text-muted-foreground">{t('screen.handover.code.formSensitiveLead')}</p>
          )}
          <SensitiveChangeList changes={preflight.sensitiveChanges} headingId={`${ids}-sensitive`} level={2} />
        </div>
      )}
      <section aria-labelledby={`${ids}-items`} className="flex flex-col gap-3">
        <h2 id={`${ids}-items`} className="text-h2">
          {t('screen.handover.form.items')}
        </h2>
        {preflight.workItems.length === 0 && (
          <p className="text-body-sm text-muted-foreground">{t('screen.handover.form.noItems')}</p>
        )}
        <ul className="flex flex-col gap-2">
          {preflight.workItems.map((item) => (
            <li key={item.id} className="flex flex-col gap-2 rounded-md border border-border p-3 text-body-sm">
              <span className="flex flex-wrap items-center gap-2">
                <code className="font-mono text-caption">{item.key}</code>
                <span className="break-words">{item.title}</span>
                {item.approved ? (
                  <span className="flex items-center gap-1 text-caption text-success">
                    <Check aria-hidden="true" className="size-4" />
                    {t('screen.handover.form.approved')}
                  </span>
                ) : (
                  <span className="text-caption text-muted-foreground">{t(`screen.board.status.${item.status}` as MessageKey)}</span>
                )}
              </span>
              {!item.approved && (
                <>
                  <label htmlFor={`${ids}-item-${item.id}`} className="text-label">
                    {t('screen.handover.form.itemNote', { key: item.key })}
                  </label>
                  <textarea
                    id={`${ids}-item-${item.id}`}
                    value={itemNotes[item.id] ?? ''}
                    maxLength={2000}
                    rows={2}
                    onChange={(event) => setItemNotes({ ...itemNotes, [item.id]: event.target.value })}
                    className={inputClass}
                  />
                </>
              )}
            </li>
          ))}
        </ul>
      </section>
      <section aria-labelledby={`${ids}-criteria`} className="flex flex-col gap-3">
        <h2 id={`${ids}-criteria`} className="text-h2">
          {t('screen.handover.form.criteria')}
        </h2>
        <ol className="flex flex-col gap-3">
          {preflight.criteria.map((criterion) => {
            const mark = marks[criterion.criterionId] ?? { note: '' }
            const set = (next: Partial<Mark>) => setMarks({ ...marks, [criterion.criterionId]: { ...mark, ...next } })
            return (
              <li key={criterion.criterionId} className="flex flex-col gap-2 rounded-lg border border-border bg-card p-4 text-body-sm">
                <fieldset className="flex flex-col gap-2">
                  <legend className="font-semibold break-words">
                    {t('screen.handover.report.criterion', { n: criterion.number, statement: criterion.statement })}
                  </legend>
                  <p className="rounded-md bg-muted p-2 break-words whitespace-pre-wrap">
                    <span className="text-label">{t('screen.handover.report.checkSteps')}: </span>
                    {criterion.checkSteps}
                  </p>
                  <div className="flex flex-wrap gap-4">
                    <label className="flex min-h-tap-min items-center gap-2">
                      <input
                        type="radio"
                        name={`${ids}-met-${criterion.criterionId}`}
                        checked={mark.met === true}
                        onChange={() => set({ met: true })}
                        className="size-4"
                      />
                      {t('screen.handover.form.met')}
                    </label>
                    <label className="flex min-h-tap-min items-center gap-2">
                      <input
                        type="radio"
                        name={`${ids}-met-${criterion.criterionId}`}
                        checked={mark.met === false}
                        onChange={() => set({ met: false })}
                        className="size-4"
                      />
                      {t('screen.handover.form.notMet')}
                    </label>
                  </div>
                  <label htmlFor={`${ids}-note-${criterion.criterionId}`} className="text-label">
                    {t('screen.handover.form.note', { n: criterion.number })}
                  </label>
                  <textarea
                    id={`${ids}-note-${criterion.criterionId}`}
                    value={mark.note}
                    maxLength={2000}
                    rows={2}
                    onChange={(event) => set({ note: event.target.value })}
                    className={inputClass}
                  />
                </fieldset>
              </li>
            )
          })}
        </ol>
      </section>
      <EvidenceFields
        jobId={jobId}
        required={preflight.previewRequired}
        uploads={preflight.screenshots}
        value={evidence}
        onChange={setEvidence}
      />
      {preflight.report && (
        <section aria-labelledby={`${ids}-report`} className="flex flex-col gap-2 rounded-lg border border-border bg-card p-4 text-body-sm">
          <h2 id={`${ids}-report`} className="text-h3">
            {t('screen.handover.form.report')}
          </h2>
          <p>{t('screen.handover.form.reportVersion', { title: preflight.report.title, version: preflight.report.versionNo })}</p>
          <Link
            to={`/cong-viec/${jobId}/tai-lieu/${preflight.report.pageId}`}
            className="w-fit text-primary underline-offset-4 hover:underline"
          >
            {t('screen.handover.form.openReport')}
          </Link>
        </section>
      )}
      {!preflight.assessment && (
        <section
          aria-labelledby={`${ids}-team-code`}
          className="flex flex-col gap-2 rounded-lg border border-primary-border bg-background p-4"
        >
          <h2 id={`${ids}-team-code`} className="text-label font-semibold">
            {t('notice.teamCode.title')}
          </h2>
          <label className="flex cursor-pointer items-start gap-3">
            <input
              type="checkbox"
              checked={teamCode}
              onChange={(event) => setTeamCode(event.target.checked)}
              className="mt-0.5 size-5 shrink-0 accent-primary"
            />
            <span className="text-body-sm break-words">{t('notice.teamCode.checkbox')}</span>
          </label>
          <p className="text-caption text-muted-foreground">
            {t('notice.teamCode.versionLine', { version: TEAM_CODE_NOTICE_VERSION })}
          </p>
        </section>
      )}
      {error && (
        <p role="alert" className="text-body-sm text-destructive">
          {error}
        </p>
      )}
      {noticeChanged && (
        <div role="alert" className="flex flex-col gap-2 text-body-sm">
          <p>{t('screen.handover.form.noticeChanged')}</p>
          <Button type="button" variant="secondary" className="w-fit" onClick={() => window.location.reload()}>
            {t('screen.handover.form.reload')}
          </Button>
        </div>
      )}
      {waiting && copied && <CopyRunBanner submission={copied} />}
      {(notCopied ?? unread) && (
        <p role="alert" className="flex gap-2 rounded-md border-l-4 border-destructive bg-destructive-soft p-3 text-body-sm">
          <TriangleAlert aria-hidden="true" className="size-5 shrink-0 text-destructive" />
          {notCopied ?? unread}
        </p>
      )}
      <div className="flex flex-wrap items-center justify-end gap-3">
        {!ready && preflight.problems.length === 0 && (
          <p className="text-caption text-muted-foreground">{t('screen.handover.form.incomplete')}</p>
        )}
        {waiting && !copied && (
          <p role="status" aria-live="polite" className="text-body-sm text-muted-foreground">
            {t('screen.handover.form.copying', { n: preflight.milestone.number })}
          </p>
        )}
        <Button type="button" disabled={!ready || waiting || handOver.isPending} onClick={() => send('CHECK')}>
          {t('screen.handover.form.send')}
        </Button>
      </div>
      {matches && (
        <SensitiveDialog matches={matches} pending={handOver.isPending} onMask={() => send('MASK')} onEdit={() => setMatches(undefined)} />
      )}
    </div>
  )
}
