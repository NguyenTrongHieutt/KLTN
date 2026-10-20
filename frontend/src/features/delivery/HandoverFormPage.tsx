import { Check, TriangleAlert } from 'lucide-react'
import { useId, useState } from 'react'
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
import { isNotFound, problemMessage } from '@/lib/problemMessage'
import { useHandOver, useHandoverPreflight, type HandoverPreflight } from './deliveryApi'

interface Mark {
  met?: boolean
  note: string
}

/**
 * "Tạo bàn giao" (FR-10, FR-32, AD-23, UX-DR54), a focused route for the Mentor: the preflight of the
 * Milestone waiting for its handover. Every Work Item in "Xong" through an approval or a note for it;
 * every Acceptance Criterion "Đạt" or "Chưa đạt" with a note, next to "Cách kiểm tra"; for an Assessment
 * Job, the Report version to hand over. "Gửi bàn giao" enables only when all of it passes, and sends
 * the Job version the form loaded.
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
  const unfinished = preflight.workItems.filter((item) => !item.approved)
  const criteriaReady = preflight.criteria.every((criterion) => {
    const mark = marks[criterion.criterionId]
    return mark?.met !== undefined && mark.note.trim() !== ''
  })
  const itemsReady = unfinished.every((item) => (itemNotes[item.id] ?? '').trim() !== '')
  const ready = preflight.problems.length === 0 && criteriaReady && itemsReady

  const send = (sensitive: 'CHECK' | 'MASK') => {
    setError(undefined)
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
      },
      {
        onSuccess: (report) => {
          setMatches(undefined)
          toasts.success(t('screen.handover.form.sent', { n: report.number }))
          void navigate(`/cong-viec/${jobId}/ban-giao/${report.id}`)
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
      {error && (
        <p role="alert" className="text-body-sm text-destructive">
          {error}
        </p>
      )}
      <div className="flex flex-wrap items-center justify-end gap-3">
        {!ready && preflight.problems.length === 0 && (
          <p className="text-caption text-muted-foreground">{t('screen.handover.form.incomplete')}</p>
        )}
        <Button type="button" disabled={!ready || handOver.isPending} onClick={() => send('CHECK')}>
          {t('screen.handover.form.send')}
        </Button>
      </div>
      {matches && (
        <SensitiveDialog matches={matches} pending={handOver.isPending} onMask={() => send('MASK')} onEdit={() => setMatches(undefined)} />
      )}
    </div>
  )
}
