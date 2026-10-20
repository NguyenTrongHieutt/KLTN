import { Dialog } from '@base-ui/react/dialog'
import { Plus, TriangleAlert, X } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { FormField } from '@/components/FormField'
import { fieldProps, inputClass } from '@/components/formField-props'
import { RegionState } from '@/components/RegionState'
import { Sheet } from '@/components/Sheet'
import { Skeleton } from '@/components/Skeleton'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t, type MessageKey } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatDayMonthTime } from '@/lib/dates'
import { problemMessage } from '@/lib/problemMessage'
import { MoveMenu } from './MoveMenu'
import {
  PRIORITIES,
  TYPES,
  useSaveWorkItem,
  useWorkItem,
  type Board,
  type WorkItemDetail,
  type WorkItemRequest,
} from './workApi'

interface Match {
  kind: string
  type: string
  masked: string
}

/**
 * The Work Item detail (FR-23, AD-23, AD-33, AD-39, UX-DR36) in the Sheet: the form for the Mentor and
 * the active Crew (assignee and priority only for the Mentor; a seeded Story's type and Milestone
 * read-only), the read-only fields for anyone else, and the history. "Thêm việc" opens it empty. A
 * save carries the version it was made on; a newer one opens the Stale dialog, and a likely credential
 * or customer data opens the Sensitive-content dialog before anything is stored.
 */
export function WorkItemSheet({
  board,
  workItemId,
  open,
  onClose,
}: {
  board: Board
  /** The Work Item to show; none for "Thêm việc". */
  workItemId: string | undefined
  open: boolean
  onClose: () => void
}) {
  const detail = useWorkItem(open ? workItemId : undefined)
  const title = workItemId ? (detail.data?.item.key ?? t('screen.board.item.title')) : t('screen.board.item.new')
  return (
    <Sheet open={open} onClose={onClose} title={title}>
      {workItemId ? (
        <RegionState query={detail} skeleton={<Skeleton className="h-96 w-full" />}>
          {detail.data && (
            <Editor
              key={`${detail.data.item.id}-${detail.data.item.version}`}
              board={board}
              detail={detail.data}
              onSaved={() => undefined}
              onStale={() => void detail.refetch()}
            />
          )}
        </RegionState>
      ) : (
        <Editor board={board} onSaved={onClose} onStale={onClose} />
      )}
    </Sheet>
  )
}

interface Line {
  text: string
  done: boolean
}

function Editor({
  board,
  detail,
  onSaved,
  onStale,
}: {
  board: Board
  detail?: WorkItemDetail
  onSaved: () => void
  onStale: () => void
}) {
  const ids = useId()
  const toasts = useToasts()
  const item = detail?.item
  const save = useSaveWorkItem(board.boardId, item?.id)
  const readOnly = board.readOnly || (detail?.readOnly ?? false)
  const seeded = item?.seeded ?? false
  const [type, setType] = useState<WorkItemRequest['type']>((item?.type as WorkItemRequest['type']) ?? 'TASK')
  const [title, setTitle] = useState(item?.title ?? '')
  const [description, setDescription] = useState(item?.description ?? '')
  const [lines, setLines] = useState<Line[]>(detail?.checklist ?? [])
  const [milestoneId, setMilestoneId] = useState(item?.milestoneId ?? board.milestones[0]?.milestoneId ?? '')
  const [priority, setPriority] = useState(item?.priority ?? '')
  const [estimate, setEstimate] = useState(item?.estimateHours != null ? String(item.estimateHours) : '')
  const [assigneeId, setAssigneeId] = useState(item?.assignee?.accountId ?? '')
  const [titleError, setTitleError] = useState<string>()
  const [error, setError] = useState<string>()
  const [matches, setMatches] = useState<Match[]>()
  const [stale, setStale] = useState(false)

  const submit = (sensitive: 'CHECK' | 'MASK') => {
    if (title.trim() === '') {
      setTitleError(t('screen.board.item.titleRequired'))
      return
    }
    setTitleError(undefined)
    const body: WorkItemRequest = {
      type,
      title: title.trim(),
      description,
      checklist: lines.filter((line) => line.text.trim() !== ''),
      milestoneId,
      priority: priority === '' ? undefined : (priority as WorkItemRequest['priority']),
      estimateHours: estimate === '' ? undefined : Number(estimate),
      assigneeId: assigneeId === '' ? undefined : assigneeId,
      version: item?.version,
      sensitive,
    }
    save.mutate(body, {
      onSuccess: (saved) => {
        setMatches(undefined)
        setError(undefined)
        toasts.success(t('screen.board.item.saved', { key: saved.item.key }))
        onSaved()
      },
      onError: (failure) => {
        if (failure instanceof ApiError && failure.problem.code === 'STALE') {
          setStale(true)
          return
        }
        if (failure instanceof ApiError && failure.problem.reason === 'SENSITIVE_CONTENT') {
          setMatches((failure.problem as { matches?: Match[] }).matches ?? [])
          return
        }
        setMatches(undefined)
        setError(problemMessage(failure, { module: 'work' }).text)
      },
    })
  }

  const onSubmit = (event: FormEvent) => {
    event.preventDefault()
    submit('CHECK')
  }

  const card = item ? (board.items.find((other) => other.id === item.id) ?? item) : undefined
  return (
    <div className="flex flex-col gap-5">
      {card && (
        <div className="flex items-center justify-between gap-2">
          <span className="text-body-sm text-muted-foreground">
            {t(`screen.board.status.${card.status}` as MessageKey)}
          </span>
          <MoveMenu board={board} item={card} jobId={board.boardId} />
        </div>
      )}
      {seeded && item?.criterion && (
        <p className="rounded-md bg-muted px-3 py-2 text-body-sm text-muted-foreground">
          {t('screen.board.item.seededNote', { m: item.criterion.milestoneNumber, n: item.criterion.number })}
        </p>
      )}
      <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
        <FormField id={`${ids}-type`} label={t('screen.board.item.type')}>
          <select
            {...fieldProps(`${ids}-type`)}
            value={type}
            disabled={readOnly || seeded}
            onChange={(event) => setType(event.target.value as WorkItemRequest['type'])}
            className={inputClass}
          >
            {TYPES.map((value) => (
              <option key={value} value={value}>
                {t(`screen.board.type.${value}` as MessageKey)}
              </option>
            ))}
          </select>
        </FormField>
        <FormField id={`${ids}-title`} label={t('screen.board.item.titleLabel')} error={titleError}>
          <input
            {...fieldProps(`${ids}-title`, titleError)}
            value={title}
            maxLength={500}
            readOnly={readOnly}
            onChange={(event) => setTitle(event.target.value)}
            className={inputClass}
          />
        </FormField>
        <FormField id={`${ids}-description`} label={t('screen.board.item.description')}>
          <textarea
            {...fieldProps(`${ids}-description`)}
            value={description}
            maxLength={4000}
            rows={4}
            readOnly={readOnly}
            onChange={(event) => setDescription(event.target.value)}
            className={inputClass}
          />
        </FormField>
        <fieldset className="flex flex-col gap-2">
          <legend className="text-label">{t('screen.board.item.checklist')}</legend>
          {lines.length === 0 && <p className="text-caption text-muted-foreground">{t('screen.board.item.noChecklist')}</p>}
          {lines.map((line, index) => (
            <div key={index} className="flex items-center gap-2">
              <input
                type="checkbox"
                checked={line.done}
                disabled={readOnly}
                aria-label={t('screen.board.item.lineDone', { n: index + 1 })}
                onChange={(event) =>
                  setLines(lines.map((other, at) => (at === index ? { ...other, done: event.target.checked } : other)))
                }
                className="size-4"
              />
              <input
                value={line.text}
                maxLength={300}
                readOnly={readOnly}
                aria-label={t('screen.board.item.line', { n: index + 1 })}
                onChange={(event) =>
                  setLines(lines.map((other, at) => (at === index ? { ...other, text: event.target.value } : other)))
                }
                className={inputClass}
              />
              {!readOnly && (
                <button
                  type="button"
                  aria-label={t('screen.board.item.removeLine', { n: index + 1 })}
                  onClick={() => setLines(lines.filter((_, at) => at !== index))}
                  className="rounded p-1 text-muted-foreground hover:bg-muted"
                >
                  <X aria-hidden="true" className="size-4" />
                </button>
              )}
            </div>
          ))}
          {!readOnly && lines.length < 20 && (
            <Button
              type="button"
              variant="ghost"
              size="compact"
              className="self-start"
              onClick={() => setLines([...lines, { text: '', done: false }])}
            >
              <Plus aria-hidden="true" />
              {t('screen.board.item.addLine')}
            </Button>
          )}
        </fieldset>
        <FormField id={`${ids}-milestone`} label={t('screen.board.item.milestone')}>
          <select
            {...fieldProps(`${ids}-milestone`)}
            value={milestoneId}
            disabled={readOnly || seeded}
            onChange={(event) => setMilestoneId(event.target.value)}
            className={inputClass}
          >
            {board.milestones.map((milestone) => (
              <option key={milestone.milestoneId} value={milestone.milestoneId}>
                {t('screen.board.item.milestoneOption', { n: milestone.number, title: milestone.title })}
              </option>
            ))}
          </select>
        </FormField>
        <div className="grid gap-4 sm:grid-cols-2">
          <FormField
            id={`${ids}-priority`}
            label={t('screen.board.item.priority')}
            helper={board.mentor ? undefined : t('screen.board.item.mentorOnly')}
          >
            <select
              {...fieldProps(`${ids}-priority`, undefined, board.mentor ? undefined : 'helper')}
              value={priority}
              disabled={readOnly || !board.mentor}
              onChange={(event) => setPriority(event.target.value)}
              className={inputClass}
            >
              <option value="">{t('screen.board.item.noPriority')}</option>
              {PRIORITIES.map((value) => (
                <option key={value} value={value}>
                  {t(`screen.board.priority.${value}` as MessageKey)}
                </option>
              ))}
            </select>
          </FormField>
          <FormField id={`${ids}-estimate`} label={t('screen.board.item.estimate')}>
            <input
              {...fieldProps(`${ids}-estimate`)}
              type="number"
              inputMode="decimal"
              min={0.5}
              max={999}
              step={0.5}
              value={estimate}
              readOnly={readOnly}
              onChange={(event) => setEstimate(event.target.value)}
              className={inputClass}
            />
          </FormField>
        </div>
        <FormField
          id={`${ids}-assignee`}
          label={t('screen.board.item.assignee')}
          helper={board.mentor ? undefined : t('screen.board.item.mentorOnly')}
        >
          <select
            {...fieldProps(`${ids}-assignee`, undefined, board.mentor ? undefined : 'helper')}
            value={assigneeId}
            disabled={readOnly || !board.mentor}
            onChange={(event) => setAssigneeId(event.target.value)}
            className={inputClass}
          >
            <option value="">{t('screen.board.unassigned')}</option>
            {item?.assignee && !board.crew.some((person) => person.accountId === item.assignee?.accountId) && (
              <option value={item.assignee.accountId}>{item.assignee.name}</option>
            )}
            {board.crew.map((person) => (
              <option key={person.accountId} value={person.accountId}>
                {person.name}
              </option>
            ))}
          </select>
        </FormField>
        {error && (
          <p role="alert" className="text-body-sm text-destructive">
            {error}
          </p>
        )}
        {!readOnly && (
          <Button type="submit" className="self-end" disabled={save.isPending}>
            {t('screen.board.item.save')}
          </Button>
        )}
      </form>
      {detail && <History detail={detail} />}
      {matches && (
        <SensitiveDialog matches={matches} pending={save.isPending} onMask={() => submit('MASK')} onEdit={() => setMatches(undefined)} />
      )}
      <StaleDialog
        open={stale}
        onReload={() => {
          setStale(false)
          onStale()
        }}
      />
    </div>
  )
}

const FIELD_LABELS: Record<string, MessageKey> = {
  type: 'screen.board.item.type',
  title: 'screen.board.item.titleLabel',
  description: 'screen.board.item.description',
  checklist: 'screen.board.item.checklist',
  milestoneId: 'screen.board.item.milestone',
  priority: 'screen.board.item.priority',
  estimateHours: 'screen.board.item.estimate',
  assigneeId: 'screen.board.item.assignee',
}

function History({ detail }: { detail: WorkItemDetail }) {
  return (
    <section aria-labelledby="work-item-history" className="flex flex-col gap-2 border-t border-border pt-4">
      <h3 id="work-item-history" className="text-h3">
        {t('screen.board.item.history')}
      </h3>
      <ol className="flex flex-col gap-1.5 text-body-sm">
        {detail.history.map((entry) => {
          const fields = entry.fields
            .filter((field) => FIELD_LABELS[field])
            .map((field) => t(FIELD_LABELS[field]).toLowerCase())
            .join(', ')
          let text: string
          if (entry.action === 'SEEDED') {
            text = t('screen.board.item.historySeeded')
          } else if (entry.action === 'CREATED') {
            text = t('screen.board.item.historyCreated', { actor: entry.actor })
          } else if (entry.assignedTo) {
            text = t('screen.board.item.historyAssigned', { actor: entry.actor, assignee: entry.assignedTo })
          } else {
            text = t('screen.board.item.historyUpdated', { actor: entry.actor, fields })
          }
          return (
            <li key={entry.id} className="flex flex-wrap justify-between gap-2">
              <span className="break-words">{text}</span>
              <span className="text-caption text-muted-foreground">{formatDayMonthTime(entry.at)}</span>
            </li>
          )
        })}
      </ol>
    </section>
  )
}

/** FR-47 for Work Items: "Che và lưu" stores the masked text; "Sửa lại" goes back to the form. */
function SensitiveDialog({
  matches,
  pending,
  onMask,
  onEdit,
}: {
  matches: Match[]
  pending: boolean
  onMask: () => void
  onEdit: () => void
}) {
  return (
    <Dialog.Root open onOpenChange={(next) => !next && onEdit()}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-[60] bg-foreground/50" />
        <Dialog.Popup className="fixed top-24 left-1/2 z-[70] flex w-[min(520px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
          <Dialog.Title className="text-h2">{t('screen.board.item.sensitiveTitle')}</Dialog.Title>
          <div className="flex gap-3 rounded-md border-l-4 border-destructive bg-destructive-soft p-4 text-body-sm">
            <TriangleAlert aria-hidden="true" className="size-5 shrink-0 text-destructive" />
            <div className="flex flex-col gap-1.5">
              <p>{t('screen.chat.warning.found')}</p>
              <ul className="flex flex-col gap-1">
                {matches.map((match, index) => (
                  <li key={`${match.masked}-${index}`} className="font-mono break-all">
                    {match.masked}
                  </li>
                ))}
              </ul>
              <Dialog.Description>{t('screen.board.item.sensitiveBody')}</Dialog.Description>
            </div>
          </div>
          <div className="flex flex-wrap justify-end gap-3">
            <Button type="button" variant="secondary" onClick={onEdit}>
              {t('screen.chat.warning.edit')}
            </Button>
            <Button type="button" disabled={pending} onClick={onMask}>
              {t('screen.board.item.mask')}
            </Button>
          </div>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}

/** The Stale dialog (UX-DR26): the edit was not saved; reload and edit again, never re-send. */
function StaleDialog({ open, onReload }: { open: boolean; onReload: () => void }) {
  return (
    <Dialog.Root open={open} onOpenChange={(next) => !next && onReload()}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-[60] bg-foreground/50" />
        <Dialog.Popup className="fixed top-24 left-1/2 z-[70] flex w-[min(480px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
          <Dialog.Title className="text-h2">{t('error.STALE')}</Dialog.Title>
          <Dialog.Description className="text-body-sm">{t('screen.board.item.staleBody')}</Dialog.Description>
          <div className="flex justify-end">
            <Button type="button" onClick={onReload}>
              {t('screen.matching.stale.reload')}
            </Button>
          </div>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
