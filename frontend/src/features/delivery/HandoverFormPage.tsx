import { TriangleAlert } from 'lucide-react'
import { useEffect, useId, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import { usePageTitle } from '@/app/usePageTitle'
import { inputClass } from '@/components/formField-props'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
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
import { MAX_SCREENSHOTS, NO_EVIDENCE, evidenceBody, evidenceReady, newLink, type Evidence } from './evidenceRules'
import { SensitiveChangeList } from './SensitiveChangeList'
import { Page } from '@/components/ui/page'

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
 * Milestone waiting for its handover: every Acceptance Criterion "Đạt" or "Chưa đạt" with a note, next to "Cách kiểm tra" -- on an Assessment
 * Job those criteria are the Assessment Report's own sections (Story 24.1) and the report itself a "Tài liệu" link
 * (Story 24.3); for a Code Job, its new commits and the Sensitive change preview, and the preview link, or a reason
 * with a "Bản ghi / video" link (Story 8.3). Then the Handover evidence list: up to ten links, each with its kind,
 * label and domain and never opened by vibefix, and up to five images (Story 24.3). A Code Job also needs
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
    <Page width="focused">
      <RegionState query={preflight} skeleton={<Skeleton className="h-96 w-full" />}>
        {preflight.data && <Form jobId={jobId} preflight={preflight.data} onStale={() => void preflight.refetch()} />}
      </RegionState>
    </Page>
  )
}

function Form({ jobId, preflight, onStale }: { jobId: string; preflight: HandoverPreflight; onStale: () => void }) {
  const ids = useId()
  const toasts = useToasts()
  const navigate = useNavigate()
  const handOver = useHandOver(jobId)
  const [marks, setMarks] = useState<Record<string, Mark>>({})
  const [error, setError] = useState<string>()
  // N-33 (FR-25, R28, Story 23.8b): the Mentor's duty, which a Code Job's handover cannot go without.
  const [teamCode, setTeamCode] = useState(false)
  const [noticeChanged, setNoticeChanged] = useState(false)
  // Story 23.8a: a Code Job's handover waits while the runner copies it, so the form reads its submission.
  const [copying, setCopying] = useState<string>()
  const submission = useHandoverSubmission(jobId, copying !== undefined)
  // Only this send's own submission decides: an earlier one the cache still holds is not it.
  const copied = copying !== undefined && submission.data?.id === copying ? submission.data : undefined
  // An Assessment Job's report goes in as a "Tài liệu" link, so its form starts with that row (FR-10, Story 24.3).
  const [evidence, setEvidence] = useState<Evidence>(() => ({
    ...NO_EVIDENCE,
    links: preflight.assessment ? [newLink('DOCUMENT')] : [],
    screenshotIds: preflight.screenshots.slice(0, MAX_SCREENSHOTS).map((upload) => upload.fileId),
  }))
  // Story 24.2: every criterion marked with a note is the whole of FR-32's enforcement.
  const criteriaReady = preflight.criteria.every((criterion) => {
    const mark = marks[criterion.criterionId]
    return mark?.met !== undefined && mark.note.trim() !== ''
  })
  // An Assessment Job hands over no code, so N-33 is neither shown nor needed there.
  const teamCodeReady = preflight.assessment || teamCode
  const ready =
    preflight.problems.length === 0 &&
    criteriaReady &&
    teamCodeReady &&
    evidenceReady(evidence, preflight.previewRequired, preflight.assessment)

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

  // D5 (Story 24.4b): the notes and the reason go as typed, and nothing checks or masks them.
  const send = () => {
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
        teamCodeNoticeVersion: preflight.assessment ? undefined : TEAM_CODE_NOTICE_VERSION,
        ...evidenceBody(evidence, preflight.assessment),
      },
      {
        onSuccess: (sent) => {
          if (sent.state === 'DONE' && sent.handoverId) {
            toasts.success(t('screen.handover.form.sent', { n: sent.number }))
            void navigate(`/cong-viec/${jobId}/ban-giao/${sent.handoverId}`)
            return
          }
          // The read is enabled by this state, so it starts on its own: one fetch per poll, no extra one here.
          setCopying(sent.id)
        },
        onError: (failure) => {
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
            <li key={problem} role="alert" className="flex gap-2 rounded-md border-l-4 border-destructive bg-destructive-soft p-4 text-body-sm">
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
      <section aria-labelledby={`${ids}-criteria`} className="flex flex-col gap-3">
        <h2 id={`${ids}-criteria`} className="text-h2">
          {t('screen.handover.form.criteria')}
        </h2>
        <ol className="flex flex-col gap-3">
          {preflight.criteria.map((criterion) => {
            const mark = marks[criterion.criterionId] ?? { note: '' }
            const set = (next: Partial<Mark>) => setMarks({ ...marks, [criterion.criterionId]: { ...mark, ...next } })
            return (
              <li key={criterion.criterionId} className="flex flex-col gap-2 rounded-lg border border-border bg-card text-body-sm p-4 md:p-5">
                <fieldset className="flex flex-col gap-2">
                  <legend className="font-semibold break-words">
                    {t('screen.handover.report.criterion', { n: criterion.number, statement: criterion.statement })}
                  </legend>
                  <p className="rounded-md bg-muted p-2 break-words whitespace-pre-wrap">
                    <span className="text-label">{t('screen.handover.report.checkSteps')}: </span>
                    {criterion.checkSteps}
                  </p>
                  <div className="flex flex-wrap gap-4">
                    <label className="flex min-h-tap-min md:min-h-control-compact items-center gap-2">
                      <input
                        type="radio"
                        name={`${ids}-met-${criterion.criterionId}`}
                        checked={mark.met === true}
                        onChange={() => set({ met: true })}
                        className="size-4"
                      />
                      {t('screen.handover.form.met')}
                    </label>
                    <label className="flex min-h-tap-min md:min-h-control-compact items-center gap-2">
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
        assessment={preflight.assessment}
        uploads={preflight.screenshots}
        value={evidence}
        onChange={setEvidence}
      />
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
        <p role="alert" className="flex gap-2 rounded-md border-l-4 border-destructive bg-destructive-soft p-4 text-body-sm">
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
        <Button type="button" disabled={!ready || waiting || handOver.isPending} onClick={send}>
          {t('screen.handover.form.send')}
        </Button>
      </div>
    </div>
  )
}
