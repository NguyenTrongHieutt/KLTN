import { useId, useState, type FormEvent } from 'react'
import { inputClass } from '@/components/formField-props'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t, type MessageKey } from '@/i18n/t'
import { formatDayMonthTime } from '@/lib/dates'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import {
  useAbuseReport,
  useAbuseReportCommand,
  useAbuseReports,
  useReportedMessage,
  useReportedNote,
  type AbuseAction,
  type AbuseReport,
} from './adminAbuseReportsApi'

/**
 * ADM-6's "Báo cáo vi phạm" tab (FR-69, FR-60, AD-20, UX-DR16): the queue, oldest open first, with titles and
 * names, never a message's or a note's text; "Nhận xử lý", after which the assignee reads the one reported
 * message or note (each read audited); and the decision: hide through FR-60, record the action taken, or
 * dismiss, with the outcome the reporter reads.
 */
export function AbuseReportsPanel() {
  const reports = useAbuseReports()
  const [current, setCurrent] = useState<string>()
  return (
    <div className="grid gap-5 lg:grid-cols-[minmax(0,320px)_minmax(0,1fr)]">
      <RegionState query={reports} skeleton={<Skeleton className="h-64 w-full" />}>
        {reports.data &&
          (reports.data.length === 0 ? (
            <p className="rounded-lg border border-border bg-card p-4 text-body-sm text-muted-foreground">
              {t('screen.admin.reports.empty')}
            </p>
          ) : (
            <ul aria-label={t('screen.admin.reports.listLabel')} className="flex flex-col gap-2">
              {reports.data.map((row) => (
                <li key={row.id}>
                  <button
                    type="button"
                    aria-current={row.id === current ? 'true' : undefined}
                    onClick={() => setCurrent(row.id)}
                    className={cn(
                      'flex w-full flex-col gap-0.5 rounded-md border border-border bg-card p-3 text-left text-body-sm focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring',
                      row.id === current && 'border-primary',
                    )}
                  >
                    <span className="text-caption text-muted-foreground">
                      {t(`screen.admin.reports.kind.${row.kind}` as MessageKey)} ·{' '}
                      {t(`screen.admin.reports.status.${row.status}` as MessageKey)}
                    </span>
                    <span className="text-label font-semibold break-words">{row.label}</span>
                    <span className="line-clamp-2 break-words">{row.reason}</span>
                    <span className="text-caption text-muted-foreground">
                      {[
                        row.reporterName,
                        formatDayMonthTime(row.createdAt),
                        row.assignedToMe
                          ? t('screen.admin.reports.assignedToMe')
                          : row.assigned
                            ? t('screen.admin.reports.assigned')
                            : '',
                      ]
                        .filter(Boolean)
                        .join(' · ')}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          ))}
      </RegionState>
      {current ? (
        <ReportDetail key={current} reportId={current} />
      ) : (
        <p className="text-body-sm text-muted-foreground">{t('screen.admin.reports.pick')}</p>
      )}
    </div>
  )
}

function ReportDetail({ reportId }: { reportId: string }) {
  const report = useAbuseReport(reportId)
  return (
    <RegionState query={report} skeleton={<Skeleton className="h-64 w-full" />}>
      {report.data && <Detail data={report.data} />}
    </RegionState>
  )
}

function Detail({ data }: { data: AbuseReport }) {
  const command = useAbuseReportCommand(data.id)
  const [error, setError] = useState<string>()
  const open = data.status === 'OPEN'
  return (
    <section className="flex flex-col gap-4 rounded-lg border border-border bg-card p-5 text-body-sm">
      <div className="flex flex-col gap-1">
        <span className="text-caption text-muted-foreground">{t(`screen.admin.reports.kind.${data.kind}` as MessageKey)}</span>
        <h2 className="text-h2 break-words">{data.label}</h2>
        <p className="text-caption text-muted-foreground">
          {t('screen.admin.reports.filedBy', {
            name: data.reporterName ?? t('screen.admin.reports.erased'),
            time: formatDayMonthTime(data.createdAt),
          })}
          {data.authorName ? ` · ${t('screen.admin.reports.author', { name: data.authorName })}` : ''}
        </p>
      </div>
      <div className="flex flex-col gap-1">
        <span className="text-label">{t('screen.admin.reports.reason')}</span>
        <p className="break-words whitespace-pre-line">{data.reason}</p>
      </div>
      {data.itemReadable && <ReportedItem data={data} />}
      {error && (
        <p role="alert" className="text-destructive">
          {error}
        </p>
      )}
      {!open ? (
        <div className="flex flex-col gap-1 rounded-md bg-muted p-3">
          <p className="font-semibold">{t(`screen.admin.reports.status.${data.status}` as MessageKey)}</p>
          {data.outcome && <p className="break-words whitespace-pre-line">{data.outcome}</p>}
          {data.decidedAt && <p className="text-caption text-muted-foreground">{formatDayMonthTime(data.decidedAt)}</p>}
        </div>
      ) : !data.assignedToMe ? (
        <div>
          <Button
            type="button"
            disabled={command.isPending}
            onClick={() =>
              command.mutate(
                { action: 'take' },
                {
                  onSuccess: () => setError(undefined),
                  onError: (failure) => setError(problemMessage(failure, { module: 'admin' }).text),
                },
              )
            }
          >
            {t('screen.admin.reports.take')}
          </Button>
        </div>
      ) : (
        <DecisionForm data={data} onError={setError} />
      )}
    </section>
  )
}

/** The one message or note the report names, read under its grant; the note says each read is recorded. */
function ReportedItem({ data }: { data: AbuseReport }) {
  const chat = data.kind === 'JOB_CHAT_MESSAGE' || data.kind === 'CREW_CHAT_MESSAGE'
  const message = useReportedMessage(data.targetId, chat)
  const note = useReportedNote(data.targetId, data.kind === 'WORK_ITEM_NOTE')
  const query = chat ? message : note
  return (
    <div className="flex flex-col gap-1 rounded-md border border-border p-3">
      <span className="text-label">{t('screen.admin.reports.item')}</span>
      <span className="text-caption text-muted-foreground">{t('screen.admin.reports.itemNote')}</span>
      <RegionState query={query} skeleton={<Skeleton className="h-16 w-full" />}>
        {chat && message.data && (
          <>
            <p className="text-caption text-muted-foreground">
              {message.data.author} · {formatDayMonthTime(message.data.sentAt)}
            </p>
            <p className="break-words whitespace-pre-line">{message.data.body}</p>
          </>
        )}
        {!chat && note.data && (
          <>
            <p className="text-caption text-muted-foreground">
              {note.data.itemKey} {note.data.itemTitle} · {note.data.author} · {formatDayMonthTime(note.data.at)}
            </p>
            <p className="break-words whitespace-pre-line">{note.data.text}</p>
          </>
        )}
      </RegionState>
    </div>
  )
}

function DecisionForm({ data, onError }: { data: AbuseReport; onError: (error: string | undefined) => void }) {
  const ids = useId()
  const toasts = useToasts()
  const command = useAbuseReportCommand(data.id)
  const actions: AbuseAction[] = data.hideable ? ['HIDE', 'ACTION_TAKEN', 'DISMISS'] : ['ACTION_TAKEN', 'DISMISS']
  const [action, setAction] = useState<AbuseAction>(actions[0])
  const [hideReason, setHideReason] = useState('')
  const [outcome, setOutcome] = useState('')
  const ready = outcome.trim() !== '' && (action !== 'HIDE' || hideReason.trim() !== '')

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!ready) {
      return
    }
    onError(undefined)
    command.mutate(
      {
        action: 'decide',
        body: { action, outcome: outcome.trim(), hideReason: action === 'HIDE' ? hideReason.trim() : undefined },
      },
      {
        onSuccess: () => toasts.success(t('screen.admin.reports.decided')),
        onError: (failure) => onError(problemMessage(failure, { module: 'admin' }).text),
      },
    )
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-3">
      <fieldset className="flex flex-col gap-2">
        <legend className="text-label">{t('screen.admin.reports.action')}</legend>
        {actions.map((option) => (
          <label key={option} className="flex min-h-tap-min items-center gap-2">
            <input
              type="radio"
              name={`${ids}-action`}
              checked={action === option}
              onChange={() => setAction(option)}
              className="size-4"
            />
            {t(`screen.admin.reports.actions.${option}` as MessageKey)}
          </label>
        ))}
      </fieldset>
      {action === 'HIDE' && (
        <div className="flex flex-col gap-1">
          <label htmlFor={`${ids}-hide`} className="text-label">
            {t('screen.admin.reports.hideReason')}
          </label>
          <textarea
            id={`${ids}-hide`}
            value={hideReason}
            maxLength={500}
            onChange={(event) => setHideReason(event.target.value)}
            className={cn(inputClass, 'min-h-20')}
          />
        </div>
      )}
      <div className="flex flex-col gap-1">
        <label htmlFor={`${ids}-outcome`} className="text-label">
          {t('screen.admin.reports.outcome')}
        </label>
        <textarea
          id={`${ids}-outcome`}
          value={outcome}
          maxLength={500}
          onChange={(event) => setOutcome(event.target.value)}
          className={cn(inputClass, 'min-h-20')}
        />
      </div>
      <div>
        <Button type="submit" variant={action === 'HIDE' ? 'destructive' : 'primary'} disabled={!ready || command.isPending}>
          {t('screen.admin.reports.decide')}
        </Button>
      </div>
    </form>
  )
}
