import { useId, useState, type FormEvent } from 'react'
import { inputClass } from '@/components/formField-props'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { formatDayMonthTime } from '@/lib/dates'
import { SensitiveDialog } from './SensitiveDialog'
import { entryText } from './historyText'
import { useAddNote, type HistoryEntry, type Resolution, type SensitiveMatch, type WorkItemDetail } from './workApi'
import { sensitiveMatches, workRefusal } from './workRefusal'

/**
 * "Ghi chú và lịch sử" (FR-76, AD-39, UX-DR79): one time-ordered history of changes, submissions,
 * approvals, returns, Attachment events, and notes. Notes are append-only: "Sửa lại" on one's own note
 * writes a new note that quotes it. Saving runs the FR-47 check first. Never on a Customer screen.
 */
export function NoteThread({ detail, canWrite }: { detail: WorkItemDetail; canWrite: boolean }) {
  const ids = useId()
  const toasts = useToasts()
  const add = useAddNote(detail.item.id)
  const [text, setText] = useState('')
  const [quoting, setQuoting] = useState<HistoryEntry>()
  const [error, setError] = useState<string>()
  const [matches, setMatches] = useState<SensitiveMatch[]>()

  const save = (sensitive: Resolution) => {
    if (text.trim() === '') {
      setError(t('reason.work.NOTE_EMPTY'))
      return
    }
    setError(undefined)
    add.mutate(
      { text: text.trim(), quotes: quoting?.id, sensitive },
      {
        onSuccess: () => {
          setMatches(undefined)
          setText('')
          setQuoting(undefined)
          toasts.success(t('screen.board.notes.saved'))
        },
        onError: (failure) => {
          const found = sensitiveMatches(failure)
          if (found) {
            setMatches(found)
            return
          }
          setMatches(undefined)
          setError(workRefusal(failure))
        },
      },
    )
  }

  const onSubmit = (event: FormEvent) => {
    event.preventDefault()
    save('CHECK')
  }

  return (
    <section aria-labelledby={`${ids}-title`} className="flex flex-col gap-3 border-t border-border pt-4">
      <h3 id={`${ids}-title`} className="text-h3">
        {t('screen.board.notes.title')}
      </h3>
      <ol className="flex flex-col gap-2 text-body-sm">
        {detail.history.map((entry) => (
          <li
            key={entry.id}
            className={entry.action === 'NOTE' ? 'flex flex-col gap-1 rounded-md border border-border p-3' : 'flex flex-col gap-0.5'}
          >
            <span className="flex flex-wrap items-baseline justify-between gap-2">
              <span className={entry.action === 'NOTE' ? 'font-semibold break-words' : 'break-words'}>
                {entryText(entry)}
                {entry.quoted && (
                  <span className="font-normal text-muted-foreground"> · {t('screen.board.notes.correction')}</span>
                )}
              </span>
              <span className="text-caption text-muted-foreground">{formatDayMonthTime(entry.at)}</span>
            </span>
            {entry.quoted && (
              <blockquote className="border-l-2 border-border pl-2 text-caption break-words whitespace-pre-wrap text-muted-foreground">
                {entry.quoted.text}
              </blockquote>
            )}
            {entry.comment && (
              <span
                className={
                  entry.action === 'NOTE'
                    ? 'break-words whitespace-pre-wrap'
                    : 'text-caption break-words whitespace-pre-wrap text-muted-foreground'
                }
              >
                {entry.comment}
              </span>
            )}
            {entry.action === 'NOTE' && entry.mine && canWrite && (
              <Button
                type="button"
                variant="ghost"
                size="compact"
                className="self-end"
                aria-label={t('screen.board.notes.correctLabel', { time: formatDayMonthTime(entry.at) })}
                onClick={() => {
                  setQuoting(entry)
                  setText(entry.comment ?? '')
                }}
              >
                {t('screen.board.notes.correct')}
              </Button>
            )}
          </li>
        ))}
      </ol>
      {canWrite && (
        <form onSubmit={onSubmit} className="flex flex-col gap-2" noValidate>
          {quoting && (
            <div className="flex flex-wrap items-center justify-between gap-2 rounded-md bg-muted px-3 py-2 text-caption">
              <span>{t('screen.board.notes.correcting', { time: formatDayMonthTime(quoting.at) })}</span>
              <Button
                type="button"
                variant="ghost"
                size="compact"
                onClick={() => {
                  setQuoting(undefined)
                  setText('')
                }}
              >
                {t('screen.board.notes.cancelCorrect')}
              </Button>
            </div>
          )}
          <label htmlFor={`${ids}-note`} className="text-label">
            {t('screen.board.notes.new')}
          </label>
          <textarea
            id={`${ids}-note`}
            value={text}
            maxLength={4000}
            rows={3}
            aria-describedby={`${ids}-rule`}
            onChange={(event) => setText(event.target.value)}
            className={inputClass}
          />
          <p id={`${ids}-rule`} className="text-caption text-muted-foreground">
            {t('screen.board.notes.append')}
          </p>
          {error && (
            <p role="alert" className="text-body-sm text-destructive">
              {error}
            </p>
          )}
          <Button type="submit" size="compact" className="self-end" disabled={add.isPending}>
            {t('screen.board.notes.save')}
          </Button>
        </form>
      )}
      {matches && (
        <SensitiveDialog matches={matches} pending={add.isPending} onMask={() => save('MASK')} onEdit={() => setMatches(undefined)} />
      )}
    </section>
  )
}

/** The newest notes first, for the Review dialog's "Ghi chú mới nhất". */
export function LatestNotes({ detail, count = 3 }: { detail: WorkItemDetail; count?: number }) {
  const ids = useId()
  const notes = detail.history.filter((entry) => entry.action === 'NOTE').slice(-count).reverse()
  return (
    <section aria-labelledby={`${ids}-title`} className="flex flex-col gap-2">
      <h3 id={`${ids}-title`} className="text-h3">
        {t('screen.board.notes.latest')}
      </h3>
      {notes.length === 0 ? (
        <p className="text-caption text-muted-foreground">{t('screen.board.notes.noNotes')}</p>
      ) : (
        <ul className="flex flex-col gap-2 text-body-sm">
          {notes.map((note) => (
            <li key={note.id} className="flex flex-col gap-0.5 rounded-md bg-muted px-3 py-2">
              <span className="break-words whitespace-pre-wrap">{note.comment}</span>
              <span className="text-caption text-muted-foreground">
                {t('screen.board.attachments.meta', { author: note.actor, time: formatDayMonthTime(note.at) })}
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
