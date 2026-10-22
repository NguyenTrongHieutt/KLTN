import { Dialog } from '@base-ui/react/dialog'
import { useId, useState } from 'react'
import { inputClass } from '@/components/formField-props'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t, type MessageKey } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatCalendarDate, formatDayMonthTime } from '@/lib/dates'
import { formatVnd } from '@/lib/money'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import {
  useChangeRequestCommand,
  useChangeRequests,
  useConfirmedVersions,
  type ChangeProposal,
  type ChangeRequest,
  type ChangeRequests,
} from './changeRequestApi'

/**
 * JOB-6's "Đề nghị thay đổi" (FR-36, UX-DR57): either side proposes new due dates and, in the full form, a
 * new scope, Milestone titles and prices, removed Milestones, added, edited, or removed Acceptance Criteria
 * with their steps, and added Milestones, never a share; the Mentor states the cost and time impact; the
 * Customer approves and the Mentor confirms, each through the Confirmation dialog on the loaded Job
 * version; then the new Agreement version is in force. Every confirmed version stays listed (AD-29).
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
      <FullChanges request={request} />
      {request.description && <p className="break-words whitespace-pre-line">{request.description}</p>}
      {request.impact && <p className="break-words">{t('screen.changeRequest.impactLabel', { impact: request.impact })}</p>}
    </>
  )
}

/** What a request changes beyond due dates, as both sides read it before they agree. */
function FullChanges({ request }: { request: ChangeRequest }) {
  const changes = request.changes
  const lines: string[] = []
  if (changes.scope) {
    lines.push(t('screen.changeRequest.full.scopeLine', { scope: changes.scope }))
  }
  for (const change of changes.milestones) {
    const n = change.number ?? 0
    if (change.remove) {
      lines.push(t('screen.changeRequest.full.removedLine', { n, title: change.oldTitle }))
      continue
    }
    if (change.title) {
      lines.push(t('screen.changeRequest.full.titleLine', { n, from: change.oldTitle, to: change.title }))
    }
    if (change.priceVnd != null) {
      lines.push(
        t('screen.changeRequest.full.priceLine', {
          n,
          from: change.oldPriceVnd == null ? '' : formatVnd(change.oldPriceVnd),
          to: formatVnd(change.priceVnd),
        }),
      )
    }
  }
  for (const criterion of changes.criteria) {
    const n = criterion.milestoneNumber ?? 0
    lines.push(
      criterion.kind === 'ADDED'
        ? t('screen.changeRequest.full.addedCriterionLine', { n, statement: criterion.statement ?? '' })
        : criterion.kind === 'EDITED'
          ? t('screen.changeRequest.full.editedCriterionLine', { n, from: criterion.oldStatement ?? '', to: criterion.statement ?? '' })
          : t('screen.changeRequest.full.removedCriterionLine', { n, statement: criterion.oldStatement ?? '' }),
    )
  }
  for (const milestone of changes.addedMilestones) {
    lines.push(
      t('screen.changeRequest.full.addedMilestoneLine', {
        title: milestone.title,
        price: formatVnd(milestone.priceVnd),
        date: formatCalendarDate(milestone.dueOn),
        n: milestone.criteria.length,
      }),
    )
  }
  if (lines.length === 0) {
    return null
  }
  return (
    <ul className="flex flex-col gap-0.5">
      {lines.map((line, index) => (
        <li key={`${index}-${line}`} className="break-words">
          {line}
        </li>
      ))}
    </ul>
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

type CriterionDraft = { statement: string; steps: string; remove: boolean }
type NewCriterionDraft = { key: string; statement: string; steps: string }
type MilestoneDraft = {
  title: string
  price: string
  remove: boolean
  criteria: Record<string, CriterionDraft>
  added: NewCriterionDraft[]
}
type AddedDraft = { key: string; title: string; price: string; dueOn: string; rounds: string; criteria: NewCriterionDraft[] }

let draftKeys = 0
const nextKey = () => `k${++draftKeys}`
const whole = (text: string) => (/^\d+$/.test(text.trim()) ? Number(text.trim()) : undefined)
const filled = (criterion: { statement: string; steps: string }) =>
  criterion.statement.trim() !== '' && criterion.steps.trim() !== ''

function initialDrafts(data: ChangeRequests): Record<string, MilestoneDraft> {
  const drafts: Record<string, MilestoneDraft> = {}
  for (const milestone of data.milestones) {
    const criteria: Record<string, CriterionDraft> = {}
    for (const criterion of milestone.criteria) {
      criteria[criterion.id] = { statement: criterion.statement, steps: criterion.checkSteps, remove: false }
    }
    drafts[milestone.id] = {
      title: milestone.title,
      price: milestone.priceVnd == null ? '' : String(milestone.priceVnd),
      remove: false,
      criteria,
      added: [],
    }
  }
  return drafts
}

/** The full form's body beyond due dates: only what differs from the confirmed Agreement. */
function changesOf(data: ChangeRequests, scope: string, drafts: Record<string, MilestoneDraft>, added: AddedDraft[]) {
  const milestones: NonNullable<ChangeProposal['milestones']> = []
  const editedCriteria: NonNullable<ChangeProposal['editedCriteria']> = []
  const removedCriteria: string[] = []
  const addedCriteria: NonNullable<ChangeProposal['addedCriteria']> = []
  for (const milestone of data.milestones) {
    const draft = drafts[milestone.id]
    if (!draft || !milestone.changeable) {
      continue
    }
    const title = draft.title.trim() !== milestone.title ? draft.title.trim() : undefined
    const price = whole(draft.price)
    const priceVnd = price !== undefined && price !== milestone.priceVnd ? price : undefined
    if (draft.remove || title !== undefined || priceVnd !== undefined) {
      milestones.push({ milestoneId: milestone.id, title, priceVnd, remove: draft.remove })
    }
    if (draft.remove) {
      continue
    }
    for (const criterion of milestone.criteria) {
      const edit = draft.criteria[criterion.id]
      if (edit?.remove) {
        removedCriteria.push(criterion.id)
      } else if (edit && (edit.statement.trim() !== criterion.statement || edit.steps.trim() !== criterion.checkSteps)) {
        editedCriteria.push({ criterionId: criterion.id, statement: edit.statement.trim(), checkSteps: edit.steps.trim() })
      }
    }
    for (const criterion of draft.added.filter(filled)) {
      addedCriteria.push({ milestoneId: milestone.id, statement: criterion.statement.trim(), checkSteps: criterion.steps.trim() })
    }
  }
  const addedMilestones = added
    .filter((milestone) => milestone.title.trim() !== '')
    .map((milestone) => ({
      title: milestone.title.trim(),
      priceVnd: whole(milestone.price) ?? 0,
      dueOn: milestone.dueOn,
      revisionRounds: whole(milestone.rounds) ?? 2,
      criteria: milestone.criteria.filter(filled).map((criterion) => ({
        statement: criterion.statement.trim(),
        checkSteps: criterion.steps.trim(),
      })),
    }))
  const changedScope = scope.trim() !== '' && scope.trim() !== (data.scope ?? '') ? scope.trim() : undefined
  // Only what changes is sent, so a due-date request reads as before.
  return {
    ...(changedScope !== undefined && { scope: changedScope }),
    ...(milestones.length > 0 && { milestones }),
    ...(editedCriteria.length > 0 && { editedCriteria }),
    ...(removedCriteria.length > 0 && { removedCriteria }),
    ...(addedCriteria.length > 0 && { addedCriteria }),
    ...(addedMilestones.length > 0 && { addedMilestones }),
  }
}

function ProposalForm({ jobId, data, onDone }: { jobId: string; data: ChangeRequests; onDone: () => void }) {
  const ids = useId()
  const toasts = useToasts()
  const command = useChangeRequestCommand(jobId)
  const [dates, setDates] = useState<Record<string, string>>({})
  const [scope, setScope] = useState(data.scope ?? '')
  const [drafts, setDrafts] = useState(() => initialDrafts(data))
  const [added, setAdded] = useState<AddedDraft[]>([])
  const [description, setDescription] = useState('')
  const [impact, setImpact] = useState('')
  const [error, setError] = useState<string>()
  const customer = data.audience === 'CUSTOMER'
  const items = Object.entries(dates)
    .filter(([, date]) => date !== '')
    .map(([milestoneId, newDueOn]) => ({ milestoneId, newDueOn }))
  const changes = changesOf(data, scope, drafts, added)
  const changed = Object.keys(changes).length > 0
  const ready = (items.length > 0 || changed) && (customer ? description.trim() !== '' : impact.trim() !== '')
  const update = (milestoneId: string, change: (draft: MilestoneDraft) => MilestoneDraft) =>
    setDrafts((current) => {
      const draft = current[milestoneId]
      return draft ? { ...current, [milestoneId]: change(draft) } : current
    })
  const send = () => {
    setError(undefined)
    command.mutate(
      {
        action: 'propose',
        body: {
          items,
          version: data.version,
          ...changes,
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
      <label htmlFor={`${ids}-scope`} className="text-label">
        {t('screen.changeRequest.full.scope')}
      </label>
      <textarea
        id={`${ids}-scope`}
        value={scope}
        maxLength={4000}
        onChange={(event) => setScope(event.target.value)}
        className={cn(inputClass, 'min-h-18')}
      />
      <ul className="flex flex-col gap-3">
        {data.milestones.map((milestone) => {
          const draft = drafts[milestone.id]
          return (
            <li key={milestone.id} className="flex flex-col gap-2 rounded-md border border-border p-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
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
              </div>
              {milestone.changeable && draft && (
                <details className="flex flex-col gap-2">
                  <summary className="cursor-pointer text-label text-primary">{t('screen.changeRequest.full.more')}</summary>
                  <div className="mt-2 flex flex-col gap-2">
                    <label className="flex flex-col gap-1">
                      <span className="text-caption">{t('screen.changeRequest.full.title', { n: milestone.number })}</span>
                      <input
                        value={draft.title}
                        maxLength={120}
                        onChange={(event) => update(milestone.id, (current) => ({ ...current, title: event.target.value }))}
                        className={inputClass}
                      />
                    </label>
                    <label className="flex flex-col gap-1">
                      <span className="text-caption">{t('screen.changeRequest.full.price', { n: milestone.number })}</span>
                      <input
                        inputMode="numeric"
                        value={draft.price}
                        disabled={milestone.held}
                        onChange={(event) =>
                          update(milestone.id, (current) => ({ ...current, price: event.target.value.replace(/[^0-9]/g, '') }))
                        }
                        className={inputClass}
                      />
                      {milestone.held && (
                        <span className="text-caption text-muted-foreground">{t('screen.changeRequest.full.heldPrice')}</span>
                      )}
                    </label>
                    <label className="flex items-center gap-2">
                      <input
                        type="checkbox"
                        checked={draft.remove}
                        onChange={(event) => update(milestone.id, (current) => ({ ...current, remove: event.target.checked }))}
                      />
                      {t(milestone.held ? 'screen.changeRequest.full.removeHeld' : 'screen.changeRequest.full.remove', {
                        n: milestone.number,
                      })}
                    </label>
                    {!draft.remove && (
                      <div className="flex flex-col gap-2">
                        <span className="text-label">{t('screen.changeRequest.full.criteria')}</span>
                        {milestone.criteria.map((criterion, index) => {
                          const edit = draft.criteria[criterion.id]
                          return (
                            edit && (
                              <div key={criterion.id} className="flex flex-col gap-1 rounded-md bg-muted p-2">
                                <label className="flex flex-col gap-1">
                                  <span className="text-caption">
                                    {t('screen.changeRequest.full.criterion', { n: milestone.number, m: index + 1 })}
                                  </span>
                                  <input
                                    value={edit.statement}
                                    maxLength={500}
                                    disabled={edit.remove}
                                    onChange={(event) =>
                                      update(milestone.id, (current) => ({
                                        ...current,
                                        criteria: { ...current.criteria, [criterion.id]: { ...edit, statement: event.target.value } },
                                      }))
                                    }
                                    className={inputClass}
                                  />
                                </label>
                                <label className="flex flex-col gap-1">
                                  <span className="text-caption">{t('screen.changeRequest.full.steps')}</span>
                                  <textarea
                                    value={edit.steps}
                                    maxLength={2000}
                                    disabled={edit.remove}
                                    onChange={(event) =>
                                      update(milestone.id, (current) => ({
                                        ...current,
                                        criteria: { ...current.criteria, [criterion.id]: { ...edit, steps: event.target.value } },
                                      }))
                                    }
                                    className={cn(inputClass, 'min-h-14')}
                                  />
                                </label>
                                <label className="flex items-center gap-2 text-caption">
                                  <input
                                    type="checkbox"
                                    checked={edit.remove}
                                    onChange={(event) =>
                                      update(milestone.id, (current) => ({
                                        ...current,
                                        criteria: { ...current.criteria, [criterion.id]: { ...edit, remove: event.target.checked } },
                                      }))
                                    }
                                  />
                                  {t('screen.changeRequest.full.removeCriterion', { n: milestone.number, m: index + 1 })}
                                </label>
                              </div>
                            )
                          )
                        })}
                        {draft.added.map((criterion) => (
                          <NewCriterionFields
                            key={criterion.key}
                            criterion={criterion}
                            label={t('screen.changeRequest.full.newCriterion', { n: milestone.number })}
                            onChange={(next) =>
                              update(milestone.id, (current) => ({
                                ...current,
                                added: current.added.map((found) => (found.key === criterion.key ? next : found)),
                              }))
                            }
                          />
                        ))}
                        <div>
                          <Button
                            type="button"
                            size="compact"
                            variant="secondary"
                            onClick={() =>
                              update(milestone.id, (current) => ({
                                ...current,
                                added: [...current.added, { key: nextKey(), statement: '', steps: '' }],
                              }))
                            }
                          >
                            {t('screen.changeRequest.full.addCriterion')}
                          </Button>
                        </div>
                      </div>
                    )}
                  </div>
                </details>
              )}
            </li>
          )
        })}
        {added.map((milestone, index) => (
          <li key={milestone.key} className="flex flex-col gap-2 rounded-md border border-primary p-3">
            <span className="text-label">{t('screen.changeRequest.full.newMilestone', { n: data.milestones.length + index + 1 })}</span>
            {(
              [
                ['title', 'screen.changeRequest.full.newTitle', 'text'],
                ['price', 'screen.changeRequest.full.newPrice', 'numeric'],
                ['dueOn', 'screen.changeRequest.full.newDue', 'date'],
                ['rounds', 'screen.changeRequest.full.newRounds', 'numeric'],
              ] as const
            ).map(([field, label, kind]) => (
              <label key={field} className="flex flex-col gap-1">
                <span className="text-caption">{t(label)}</span>
                <input
                  type={kind === 'date' ? 'date' : 'text'}
                  inputMode={kind === 'numeric' ? 'numeric' : undefined}
                  value={milestone[field]}
                  onChange={(event) =>
                    setAdded((current) =>
                      current.map((found) =>
                        found.key === milestone.key
                          ? { ...found, [field]: kind === 'numeric' ? event.target.value.replace(/[^0-9]/g, '') : event.target.value }
                          : found,
                      ),
                    )
                  }
                  className={inputClass}
                />
              </label>
            ))}
            {milestone.criteria.map((criterion) => (
              <NewCriterionFields
                key={criterion.key}
                criterion={criterion}
                label={t('screen.changeRequest.full.newCriterion', { n: data.milestones.length + index + 1 })}
                onChange={(next) =>
                  setAdded((current) =>
                    current.map((found) =>
                      found.key === milestone.key
                        ? { ...found, criteria: found.criteria.map((old) => (old.key === criterion.key ? next : old)) }
                        : found,
                    ),
                  )
                }
              />
            ))}
            <div className="flex flex-wrap gap-2">
              <Button
                type="button"
                size="compact"
                variant="secondary"
                onClick={() =>
                  setAdded((current) =>
                    current.map((found) =>
                      found.key === milestone.key
                        ? { ...found, criteria: [...found.criteria, { key: nextKey(), statement: '', steps: '' }] }
                        : found,
                    ),
                  )
                }
              >
                {t('screen.changeRequest.full.addCriterion')}
              </Button>
              <Button
                type="button"
                size="compact"
                variant="ghost"
                onClick={() => setAdded((current) => current.filter((found) => found.key !== milestone.key))}
              >
                {t('screen.changeRequest.full.dropMilestone')}
              </Button>
            </div>
          </li>
        ))}
      </ul>
      <div>
        <Button
          type="button"
          size="compact"
          variant="secondary"
          onClick={() =>
            setAdded((current) => [
              ...current,
              { key: nextKey(), title: '', price: '', dueOn: '', rounds: '2', criteria: [{ key: nextKey(), statement: '', steps: '' }] },
            ])
          }
        >
          {t('screen.changeRequest.full.addMilestone')}
        </Button>
      </div>
      <p className="text-caption text-muted-foreground">{t('screen.changeRequest.full.noShares')}</p>
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

function NewCriterionFields({
  criterion,
  label,
  onChange,
}: {
  criterion: NewCriterionDraft
  label: string
  onChange: (next: NewCriterionDraft) => void
}) {
  return (
    <div className="flex flex-col gap-1 rounded-md bg-primary-soft p-2">
      <label className="flex flex-col gap-1">
        <span className="text-caption">{label}</span>
        <input
          value={criterion.statement}
          maxLength={500}
          onChange={(event) => onChange({ ...criterion, statement: event.target.value })}
          className={inputClass}
        />
      </label>
      <label className="flex flex-col gap-1">
        <span className="text-caption">{t('screen.changeRequest.full.steps')}</span>
        <textarea
          value={criterion.steps}
          maxLength={2000}
          onChange={(event) => onChange({ ...criterion, steps: event.target.value })}
          className={cn(inputClass, 'min-h-14')}
        />
      </label>
    </div>
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
            <span className="text-caption break-words">{version.scope}</span>
            {version.milestones.map((milestone, index) => (
              <span key={milestone.id} className="text-caption">
                {t('screen.changeRequest.milestone', { n: index + 1, title: milestone.title })}: {due(milestone.dueOn)}
                {milestone.priceVnd != null ? ` · ${formatVnd(milestone.priceVnd)}` : ''}
                {milestone.criteria.length > 0 ? ` · ${t('screen.changeRequest.full.criteriaCount', { n: milestone.criteria.length })}` : ''}
              </span>
            ))}
          </li>
        ))}
      </ul>
    </div>
  )
}
