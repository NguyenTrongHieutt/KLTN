import { useId, useState } from 'react'
import { Link, useParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { inputClass } from '@/components/formField-props'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { TestTag } from '@/components/TestTag'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t, type MessageKey } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatDayMonth, formatDayMonthTime } from '@/lib/dates'
import { formatVnd } from '@/lib/money'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import {
  useAdminSplitPreview,
  useAdminSplitResolution,
  useAdminSplitResolutions,
  useSplitEvidenceChat,
  useSplitEvidenceCommits,
  useSplitEvidencePersonLinks,
  useSplitEvidenceVersions,
  useSplitEvidenceWorkItems,
  useSplitResolutionCommand,
  type AdminSplitParty,
  type AdminSplitResolution,
  type AdminSplitShare,
} from './adminSplitResolutionsApi'
import { CaseTabs } from './CaseTabs'
import { StepUpDialog } from './StepUpDialog'

/**
 * ADM-4's "Phân chia tiền" (FR-84, AD-20, AD-40, UX-DR107): the Split Resolutions with the "undecided past its due
 * time" filter; one with "Nhận xử lý", refused with `CONFLICT_OF_INTEREST` when the Administrator is linked to an
 * account in the case; the Split Evidence Package, each view audited by the server; and the decision: whole percentages
 * with a live sum, the server's VND preview, written reasoning, and the step-up. A decision is final and the Customer
 * never takes part in it. Once decided, every Administrator reads the record.
 */
export function AdminSplitResolutionsPage() {
  const { resolutionId } = useParams()
  usePageTitle(t('screen.admin.splitResolutions.title'))
  return (
    <div className="flex flex-col gap-5">
      <PageHeading>{t('screen.admin.disputes.title')}</PageHeading>
      <CaseTabs current="splits" />
      <div className="grid gap-5 lg:grid-cols-[minmax(0,320px)_minmax(0,1fr)]">
        <ResolutionList current={resolutionId} />
        {resolutionId ? (
          <ResolutionDetail key={resolutionId} resolutionId={resolutionId} />
        ) : (
          <p className="text-body-sm text-muted-foreground">{t('screen.admin.splitResolutions.pick')}</p>
        )}
      </div>
    </div>
  )
}

function ResolutionList({ current }: { current?: string }) {
  const [onlyLong, setOnlyLong] = useState(false)
  const resolutions = useAdminSplitResolutions(onlyLong)
  return (
    <div className="flex flex-col gap-2">
      <div role="group" aria-label={t('screen.admin.splitResolutions.filterLong')} className="flex flex-wrap gap-2">
        {[false, true].map((option) => (
          <button
            key={String(option)}
            type="button"
            aria-pressed={onlyLong === option}
            onClick={() => setOnlyLong(option)}
            className={cn(
              'min-h-control-compact rounded-full border px-3 text-label focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring',
              onlyLong === option
                ? 'border-primary bg-primary-soft text-foreground'
                : 'border-border bg-card text-muted-foreground hover:bg-muted',
            )}
          >
            {t(option ? 'screen.admin.splitResolutions.filterLong' : 'screen.admin.splitResolutions.filterAll')}
          </button>
        ))}
      </div>
      <RegionState query={resolutions} skeleton={<Skeleton className="h-64 w-full" />}>
        {resolutions.data &&
          (resolutions.data.length === 0 ? (
            <p className="text-body-sm text-muted-foreground">
              {t(onlyLong ? 'screen.admin.splitResolutions.emptyFiltered' : 'screen.admin.splitResolutions.empty')}
            </p>
          ) : (
            <ul className="flex flex-col gap-2">
              {resolutions.data.map((row) => (
                <li key={row.id}>
                  <Link
                    to={`/quan-tri/tranh-chap/phan-chia/${row.id}`}
                    aria-current={row.id === current ? 'page' : undefined}
                    className={cn(
                      'flex flex-col gap-0.5 rounded-md border border-border bg-card p-3 text-body-sm',
                      row.id === current && 'border-primary',
                    )}
                  >
                    <span className="text-label font-semibold break-words">
                      {row.jobTitle ?? t('screen.admin.splitResolutions.noTitle')}
                    </span>
                    <span className="text-caption text-muted-foreground">
                      {[
                        t('screen.admin.splitResolutions.milestone', { n: row.milestoneNumber }),
                        formatDayMonthTime(row.raisedAt),
                        t(`screen.admin.splitResolutions.status.${row.status}` as MessageKey),
                        row.assignedToMe
                          ? t('screen.admin.splitResolutions.assignedToMe')
                          : row.assigned
                            ? t('screen.admin.splitResolutions.assigned')
                            : '',
                      ]
                        .filter((part) => part !== '')
                        .join(' · ')}
                    </span>
                    {row.undecidedLong && (
                      <span className="w-fit rounded-full bg-warning-soft px-2 py-0.5 text-caption text-warning">
                        {t('screen.admin.splitResolutions.undecidedLong')}
                      </span>
                    )}
                  </Link>
                </li>
              ))}
            </ul>
          ))}
      </RegionState>
    </div>
  )
}

function ResolutionDetail({ resolutionId }: { resolutionId: string }) {
  const resolution = useAdminSplitResolution(resolutionId)
  return (
    <RegionState
      query={{ ...resolution, isError: resolution.isLoadingError }}
      skeleton={<Skeleton className="h-96 w-full" />}
    >
      {resolution.data && <Detail data={resolution.data} />}
    </RegionState>
  )
}

function refusal(error: unknown): string {
  if (error instanceof ApiError && error.problem.reason === 'ALREADY_DECIDED') {
    return t('screen.admin.splitResolutions.alreadyDecided')
  }
  return problemMessage(error, { module: 'dispute' }).text
}

/** A decision refused for the case itself, not the code: the case's words say why, and may end the form with them. */
const CASE_REFUSALS = [
  'SPLIT_RESOLUTION_ENDED',
  'NOT_ASSIGNED',
  'ALREADY_DECIDED',
  'SPLIT_INVALID',
  'CONFLICT_OF_INTEREST',
]

function isCaseRefusal(error: unknown): boolean {
  return error instanceof ApiError && CASE_REFUSALS.includes(error.problem.reason ?? '')
}

function Detail({ data }: { data: AdminSplitResolution }) {
  const toasts = useToasts()
  const command = useSplitResolutionCommand(data.id)
  const [error, setError] = useState<string>()
  const open = data.status === 'OPEN'
  return (
    <section className="flex flex-col gap-4 rounded-lg border border-border bg-card p-5 text-body-sm">
      <h2 className="text-h2 break-words">{data.jobTitle ?? t('screen.admin.splitResolutions.noTitle')}</h2>
      <p className="text-caption text-muted-foreground">
        {[
          t('screen.admin.splitResolutions.milestone', { n: data.milestoneNumber }),
          t('screen.admin.splitResolutions.raisedAt', { time: formatDayMonthTime(data.raisedAt) }),
          t(`screen.admin.splitResolutions.status.${data.status}` as MessageKey),
        ].join(' · ')}
      </p>
      <p>
        {t('screen.admin.splitResolutions.pooled', { amount: formatVnd(data.pooledVnd) })} <TestTag />
      </p>
      {open && (
        <p className={cn(data.undecidedLong && 'text-warning')}>
          {t('screen.admin.splitResolutions.days', { n: data.daysUndecided })}
        </p>
      )}
      {data.assignedToMe && (
        <>
          <div className="flex flex-col gap-1">
            <span className="text-label">{t('screen.admin.splitResolutions.raiser')}</span>
            <p className="break-words">{data.raiserName ?? t('screen.admin.splitResolutions.unnamed')}</p>
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-label">{t('screen.admin.splitResolutions.reason')}</span>
            <p className="break-words whitespace-pre-line">{data.reason}</p>
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-label">{t('screen.admin.splitResolutions.statements')}</span>
            {data.statements.length === 0 ? (
              <p className="text-muted-foreground">{t('screen.admin.splitResolutions.noStatements')}</p>
            ) : (
              <ul className="flex flex-col gap-2">
                {data.statements.map((statement) => (
                  <li key={statement.partyId} className="flex flex-col gap-0.5 rounded-md bg-muted p-3">
                    <p className="text-caption text-muted-foreground break-words">
                      {statement.name ?? t('screen.admin.splitResolutions.unnamed')} ·{' '}
                      {formatDayMonthTime(statement.submittedAt)}
                    </p>
                    <p className="break-words whitespace-pre-line">{statement.text}</p>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </>
      )}
      {error && (
        <p role="alert" className="text-destructive">
          {error}
        </p>
      )}
      {open && !data.assignedToMe && (
        <div className="flex flex-col gap-2">
          {data.assigned && (
            <p className="text-caption text-muted-foreground">{t('screen.admin.splitResolutions.takenByOther')}</p>
          )}
          <p className="text-caption text-muted-foreground">{t('screen.admin.splitResolutions.takeNote')}</p>
          <div>
            <Button
              type="button"
              disabled={command.isPending}
              onClick={() =>
                command.mutate(
                  { action: 'take' },
                  { onSuccess: () => setError(undefined), onError: (failure) => setError(refusal(failure)) },
                )
              }
            >
              {t('screen.admin.splitResolutions.take')}
            </Button>
          </div>
        </div>
      )}
      {data.decision && <Outcome data={data} />}
      {open && data.assignedToMe && (
        <DecisionForm
          data={data}
          pending={command.isPending}
          onDecide={(body, refused) => {
            setError(undefined)
            command.mutate(
              { action: 'decide', body },
              {
                onSuccess: () => toasts.success(t('screen.admin.splitResolutions.decision.decided')),
                onError: (failure) => {
                  // The case read again before this runs, and may have ended the form: its wording stays up here.
                  if (isCaseRefusal(failure)) {
                    setError(decisionRefusal(failure, data))
                  }
                  refused(failure)
                },
              },
            )
          }}
        />
      )}
      {data.evidenceReadable && <Evidence resolutionId={data.id} />}
    </section>
  )
}

function Outcome({ data }: { data: AdminSplitResolution }) {
  const decision = data.decision
  if (!decision) {
    return null
  }
  return (
    <div className="flex flex-col gap-1 rounded-md bg-muted p-3">
      <ul className="flex flex-col gap-0.5">
        {decision.lines.map((line) => (
          <li key={line.partyId} className="font-semibold">
            {t('screen.admin.splitResolutions.decision.previewLine', {
              name: line.name ?? t('screen.admin.splitResolutions.unnamed'),
              pct: line.percent,
              vnd: formatVnd(line.amountVnd),
            })}{' '}
            <TestTag />
          </li>
        ))}
      </ul>
      <p className="break-words whitespace-pre-line">{decision.reasoning}</p>
      <p className="text-caption text-muted-foreground">
        {t('screen.admin.splitResolutions.decision.at', {
          n: decision.versionNo,
          time: formatDayMonthTime(decision.decidedAt),
        })}
      </p>
      {data.jobContentGone && (
        <p className="text-caption text-muted-foreground">{t('screen.admin.splitResolutions.contentGone')}</p>
      )}
    </div>
  )
}

type DecisionBody = { shares: AdminSplitShare[]; reasoning: string; stepUpCode: string }

/** A line's broken rule, which turns it red and disables the decision; the server checks again on every call. */
function lineProblem(data: AdminSplitResolution, party: AdminSplitParty, text: string): string | null {
  if (text === '') {
    return null
  }
  const percent = Number(text)
  if (percent > 100) {
    return t('screen.admin.splitResolutions.decision.lineTooMuch')
  }
  if (party.mentor && data.cap !== undefined && percent > data.cap) {
    return t('screen.admin.splitResolutions.decision.lineOverCap', { cap: data.cap })
  }
  if (!party.mentor && data.capApplies && party.wholeInterval && percent === 0) {
    return t('screen.admin.splitResolutions.decision.lineZero')
  }
  return null
}

function DecisionForm({
  data,
  pending,
  onDecide,
}: {
  data: AdminSplitResolution
  pending: boolean
  onDecide: (body: DecisionBody, refused: (error: unknown) => void) => void
}) {
  const ids = useId()
  const [typed, setTyped] = useState<Record<string, string>>({})
  const [reasoning, setReasoning] = useState('')
  const [stepUp, setStepUp] = useState(false)
  const parties = data.parties
  const filled = parties.every((party) => (typed[party.partyId] ?? '') !== '')
  const sum = parties.reduce((total, party) => total + Number(typed[party.partyId] ?? ''), 0)
  const problems = Object.fromEntries(
    parties.map((party) => [party.partyId, lineProblem(data, party, typed[party.partyId] ?? '')]),
  )
  const valid = filled && sum === 100 && Object.values(problems).every((problem) => problem === null)
  const shares = valid
    ? parties.map((party) => ({ partyId: party.partyId, percent: Number(typed[party.partyId]) }))
    : undefined
  const preview = useAdminSplitPreview(data.id, shares)
  // The server's figures for these very percentages: nothing is decided without them.
  const previewed = valid ? preview.data : undefined
  const lines = previewed
    ? parties.map((party) => ({
        partyId: party.partyId,
        text: t('screen.admin.splitResolutions.decision.previewLine', {
          name: party.name ?? t('screen.admin.splitResolutions.unnamed'),
          pct: Number(typed[party.partyId]),
          vnd: formatVnd(previewed.lines.find((line) => line.partyId === party.partyId)?.amountVnd ?? 0),
        }),
      }))
    : undefined
  return (
    <fieldset className="flex flex-col gap-3 rounded-md border border-border bg-background p-3">
      <legend className="px-1 text-label">{t('screen.admin.splitResolutions.decision.title')}</legend>
      <div className="flex flex-col gap-2">
        {parties.map((party) => {
          const problem = problems[party.partyId]
          const hint = `${ids}-${party.partyId}-hint`
          return (
            <div key={party.partyId} className="flex flex-wrap items-center gap-3 border-b border-border py-2">
              <label htmlFor={`${ids}-${party.partyId}`} className="min-w-48 flex-1 break-words">
                {party.name ?? t('screen.admin.splitResolutions.unnamed')}
              </label>
              <span className="flex items-center gap-1">
                <input
                  id={`${ids}-${party.partyId}`}
                  inputMode="numeric"
                  value={typed[party.partyId] ?? ''}
                  aria-invalid={problem !== null}
                  aria-describedby={hint}
                  onChange={(event) =>
                    setTyped({ ...typed, [party.partyId]: event.target.value.replace(/[^0-9]/g, '').slice(0, 3) })
                  }
                  className={cn(inputClass, 'w-20', problem !== null && 'border-destructive')}
                />
                <span aria-hidden="true">%</span>
              </span>
              <span
                id={hint}
                className={cn('w-full text-caption', problem !== null ? 'text-destructive' : 'text-muted-foreground')}
              >
                {problem ??
                  (party.mentor && data.cap !== undefined
                    ? t('screen.admin.splitResolutions.decision.capHint', { cap: data.cap })
                    : null)}
              </span>
            </div>
          )
        })}
      </div>
      <p
        className={cn('text-label', filled && sum !== 100 ? 'text-destructive' : 'text-muted-foreground')}
        aria-live="polite"
      >
        {t('screen.admin.splitResolutions.decision.sum', { sum })}
      </p>
      {previewed && lines && (
        <p className="text-caption">
          {t('screen.admin.splitResolutions.decision.preview', {
            lines: lines.map((line) => line.text).join(', '),
            pooled: formatVnd(previewed.pooledVnd),
          })}{' '}
          <TestTag />
        </p>
      )}
      {preview.isError && (
        <p role="alert" className="text-destructive">
          {decisionRefusal(preview.error, data)}
        </p>
      )}
      <label htmlFor={`${ids}-reasoning`} className="text-label">
        {t('screen.admin.splitResolutions.decision.reasoning')}
      </label>
      <textarea
        id={`${ids}-reasoning`}
        value={reasoning}
        maxLength={4000}
        aria-describedby={`${ids}-reasoning-hint`}
        onChange={(event) => setReasoning(event.target.value)}
        className={cn(inputClass, 'min-h-24')}
      />
      <p id={`${ids}-reasoning-hint`} className="text-caption text-muted-foreground">
        {t('screen.admin.splitResolutions.decision.reasoningHint')}
      </p>
      <p className="text-caption text-muted-foreground">{t('screen.admin.splitResolutions.decision.final')}</p>
      <div className="flex justify-end">
        <Button
          type="button"
          variant="destructive"
          disabled={!lines || reasoning.trim() === '' || pending}
          onClick={() => setStepUp(true)}
        >
          {t('screen.admin.splitResolutions.decision.confirm')}
        </Button>
      </div>
      {stepUp && (
        <StepUpDialog
          open
          onOpenChange={setStepUp}
          confirmLabel={t('screen.admin.splitResolutions.decision.confirm')}
          pending={pending}
          summary={
            lines && (
              <ul className="flex flex-col gap-0.5 text-body-sm">
                {lines.map((line) => (
                  <li key={line.partyId} className="font-semibold break-words">
                    {line.text} <TestTag />
                  </li>
                ))}
              </ul>
            )
          }
          onConfirm={(code, refused) => {
            if (shares) {
              onDecide({ shares, reasoning: reasoning.trim(), stepUpCode: code }, (failure) => {
                if (isCaseRefusal(failure)) {
                  setStepUp(false)
                } else {
                  refused(failure)
                }
              })
            }
          }}
        />
      )}
    </fieldset>
  )
}

/** `SPLIT_INVALID` names the cap the server held the decision to. */
function decisionRefusal(error: unknown, data: AdminSplitResolution): string {
  if (error instanceof ApiError && error.problem.reason === 'SPLIT_INVALID') {
    return data.cap === undefined
      ? t('screen.admin.splitResolutions.decision.invalidNoCap')
      : t('screen.admin.splitResolutions.decision.invalid', { cap: data.cap })
  }
  return refusal(error)
}

const TABS = ['versions', 'workItems', 'commits', 'crewChat', 'personLinks'] as const

type EvidenceTab = (typeof TABS)[number]

/** The Split Evidence Package, each tab one audited read of the server's, mounted only when opened. */
function Evidence({ resolutionId }: { resolutionId: string }) {
  const [tab, setTab] = useState<EvidenceTab>('versions')
  return (
    <section aria-labelledby="split-evidence" className="flex flex-col gap-3">
      <h3 id="split-evidence" className="text-h3">
        {t('screen.admin.splitResolutions.evidence.title')}
      </h3>
      <div
        role="tablist"
        aria-label={t('screen.admin.splitResolutions.evidence.title')}
        className="flex flex-wrap gap-2"
      >
        {TABS.map((option) => (
          <button
            key={option}
            type="button"
            role="tab"
            aria-selected={tab === option}
            onClick={() => setTab(option)}
            className={cn(
              'min-h-control-compact rounded-full border px-3 text-label focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring',
              tab === option
                ? 'border-primary bg-primary-soft text-foreground'
                : 'border-border bg-card text-muted-foreground hover:bg-muted',
            )}
          >
            {t(`screen.admin.splitResolutions.evidence.${option}` as MessageKey)}
          </button>
        ))}
      </div>
      <div role="tabpanel">
        {tab === 'versions' && <VersionsTab resolutionId={resolutionId} />}
        {tab === 'workItems' && <WorkItemsTab resolutionId={resolutionId} />}
        {tab === 'commits' && <CommitsTab resolutionId={resolutionId} />}
        {tab === 'crewChat' && <CrewChatTab resolutionId={resolutionId} />}
        {tab === 'personLinks' && <PersonLinksTab resolutionId={resolutionId} />}
      </div>
    </section>
  )
}

function VersionsTab({ resolutionId }: { resolutionId: string }) {
  const versions = useSplitEvidenceVersions(resolutionId)
  const unnamed = t('screen.admin.splitResolutions.unnamed')
  return (
    <RegionState query={versions} skeleton={<Skeleton className="h-24 w-full" />}>
      {versions.data && (
        <div className="flex flex-col gap-3">
          <ul className="flex flex-col gap-1">
            {versions.data.parties.map((party) => (
              <li key={party.partyId} className="break-words">
                <span className="font-semibold">{party.name ?? unnamed}</span>{' '}
                <span className="text-caption text-muted-foreground">
                  {[
                    party.activeFrom && party.activeTo
                      ? t('screen.admin.splitResolutions.evidence.active', {
                          from: formatDayMonth(party.activeFrom),
                          to: formatDayMonth(party.activeTo),
                        })
                      : t('screen.admin.splitResolutions.evidence.activeUnknown'),
                    party.wholeInterval ? t('screen.admin.splitResolutions.evidence.whole') : '',
                    party.activeAtRelease ? t('screen.admin.splitResolutions.evidence.atRelease') : '',
                  ]
                    .filter((part) => part !== '')
                    .join(' · ')}
                </span>
              </li>
            ))}
          </ul>
          {versions.data.versions.length === 0 ? (
            <p className="text-muted-foreground">{t('screen.admin.splitResolutions.evidence.noVersions')}</p>
          ) : (
            <ul className="flex flex-col gap-2">
              {versions.data.versions.map((version) => (
                <li key={version.versionNo} className="flex flex-col gap-0.5 rounded-md bg-muted p-3">
                  <p className="font-semibold">
                    {t('screen.admin.splitResolutions.evidence.version', {
                      n: version.versionNo,
                      kind: t(`screen.admin.splitResolutions.evidence.kind.${version.kind}` as MessageKey),
                      date: formatDayMonthTime(version.createdAt),
                    })}
                  </p>
                  <p className="text-caption text-muted-foreground">
                    {[
                      version.kind === 'DEFAULT' || version.kind === 'MENTOR_ONLY'
                        ? t('screen.admin.splitResolutions.evidence.system')
                        : t('screen.admin.splitResolutions.evidence.author', { name: version.authorName ?? unnamed }),
                      version.capPercent === undefined
                        ? t('screen.admin.splitResolutions.evidence.noCap')
                        : t('screen.admin.splitResolutions.evidence.cap', { cap: version.capPercent }),
                    ].join(' · ')}
                  </p>
                  {version.lines.map((line) => (
                    <p key={line.partyId} className="text-caption">
                      {t('screen.admin.splitResolutions.evidence.line', {
                        name: versions.data.parties.find((party) => party.partyId === line.partyId)?.name ?? unnamed,
                        pct: line.percent,
                      })}
                      {line.confirmedAt
                        ? ` · ${t('screen.admin.splitResolutions.evidence.confirmed', { time: formatDayMonthTime(line.confirmedAt) })}`
                        : ''}
                    </p>
                  ))}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </RegionState>
  )
}

const ACTIONS = [
  'SEEDED',
  'CREATED',
  'UPDATED',
  'MOVED',
  'SUBMITTED',
  'APPROVED',
  'RETURNED',
  'UNREVIEWED',
  'NOTE',
  'ATTACHED',
  'DETACHED',
]

/** A history entry's action in plain words: a code never appears as text. */
function actionText(action: string): string {
  return t(`screen.admin.splitResolutions.evidence.action.${ACTIONS.includes(action) ? action : 'OTHER'}` as MessageKey)
}

function WorkItemsTab({ resolutionId }: { resolutionId: string }) {
  const items = useSplitEvidenceWorkItems(resolutionId)
  return (
    <RegionState query={items} skeleton={<Skeleton className="h-24 w-full" />}>
      {items.data &&
        (items.data.length === 0 ? (
          <p className="text-muted-foreground">{t('screen.admin.splitResolutions.evidence.noItems')}</p>
        ) : (
          <ul className="flex flex-col gap-2">
            {items.data.map((item) => (
              <li key={item.workItemId} className="flex flex-col gap-0.5 rounded-md bg-muted p-3">
                <p className="break-words">
                  <span className="font-semibold">{item.key}</span> {item.title}
                </p>
                {item.assigneeName && (
                  <p className="text-caption text-muted-foreground">
                    {t('screen.admin.splitResolutions.evidence.assignee', { name: item.assigneeName })}
                  </p>
                )}
                {item.history.map((line, index) => (
                  <p key={`${item.workItemId}-${index}`} className="text-caption break-words">
                    {t('screen.admin.splitResolutions.evidence.history', {
                      action: actionText(line.action),
                      time: formatDayMonthTime(line.at),
                    })}
                    {line.actorName ? ` · ${line.actorName}` : ''}
                    {line.comment ? ` · ${line.comment}` : ''}
                  </p>
                ))}
              </li>
            ))}
          </ul>
        ))}
    </RegionState>
  )
}

function CommitsTab({ resolutionId }: { resolutionId: string }) {
  const commits = useSplitEvidenceCommits(resolutionId)
  return (
    <RegionState query={commits} skeleton={<Skeleton className="h-24 w-full" />}>
      {commits.data &&
        (commits.data.length === 0 ? (
          <p className="text-muted-foreground">{t('screen.admin.splitResolutions.evidence.noCommits')}</p>
        ) : (
          <ul className="flex flex-col gap-2">
            {commits.data.map((commit) => (
              <li key={`${commit.handoverId}-${commit.sha}`} className="flex flex-col gap-0.5 rounded-md bg-muted p-3">
                <p className="break-words">
                  <span className="font-mono text-caption">{commit.sha.slice(0, 7)}</span> {commit.message}
                </p>
                <p className="text-caption text-muted-foreground">
                  {[
                    t('screen.admin.splitResolutions.evidence.round', { n: commit.round }),
                    commit.authorGithubId === undefined
                      ? t('screen.admin.splitResolutions.evidence.commitUnknown')
                      : commit.partyName
                        ? t('screen.admin.splitResolutions.evidence.commitAuthor', {
                            name: commit.partyName,
                            id: commit.authorGithubId,
                          })
                        : t('screen.admin.splitResolutions.evidence.commitOutside', { id: commit.authorGithubId }),
                  ].join(' · ')}
                </p>
              </li>
            ))}
          </ul>
        ))}
    </RegionState>
  )
}

function CrewChatTab({ resolutionId }: { resolutionId: string }) {
  const chat = useSplitEvidenceChat(resolutionId)
  return (
    <RegionState query={chat} skeleton={<Skeleton className="h-24 w-full" />}>
      {chat.data && (
        <div className="flex flex-col gap-2">
          <p className="text-caption text-muted-foreground">
            {chat.data.from
              ? t('screen.admin.splitResolutions.evidence.chatInterval', {
                  from: formatDayMonth(chat.data.from),
                  to: formatDayMonth(chat.data.to),
                })
              : t('screen.admin.splitResolutions.evidence.chatToOnly', { to: formatDayMonth(chat.data.to) })}
          </p>
          {chat.data.total > chat.data.messages.length && (
            <p className="text-caption text-muted-foreground">
              {t('screen.admin.splitResolutions.evidence.chatShown', {
                shown: chat.data.messages.length,
                total: chat.data.total,
              })}
            </p>
          )}
          {chat.data.messages.length === 0 ? (
            <p className="text-muted-foreground">{t('screen.admin.splitResolutions.evidence.noMessages')}</p>
          ) : (
            <ul className="flex flex-col gap-2">
              {chat.data.messages.map((message) => (
                <li key={message.messageId} className="flex flex-col gap-0.5 rounded-md bg-muted p-3">
                  <p className="text-caption text-muted-foreground break-words">
                    {message.author ?? t('screen.admin.splitResolutions.unnamed')} ·{' '}
                    {formatDayMonthTime(message.sentAt)}
                  </p>
                  <p className="break-words whitespace-pre-line">{message.body}</p>
                  {message.attachmentName && <p className="text-caption break-words">{message.attachmentName}</p>}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </RegionState>
  )
}

function PersonLinksTab({ resolutionId }: { resolutionId: string }) {
  const links = useSplitEvidencePersonLinks(resolutionId)
  const unnamed = t('screen.admin.splitResolutions.unnamed')
  const shown = (name: string | undefined, customer: boolean) =>
    customer ? `${name ?? unnamed} (${t('screen.admin.splitResolutions.evidence.customer')})` : (name ?? unnamed)
  return (
    <RegionState query={links} skeleton={<Skeleton className="h-24 w-full" />}>
      {links.data &&
        (links.data.length === 0 ? (
          <p className="text-muted-foreground">{t('screen.admin.splitResolutions.evidence.noPairs')}</p>
        ) : (
          <ul className="flex flex-col gap-2">
            {links.data.map((pair, index) => (
              <li key={index} className="flex flex-col gap-0.5 rounded-md bg-muted p-3">
                <p className="font-semibold break-words">
                  {t('screen.admin.splitResolutions.evidence.pair', {
                    first: shown(pair.firstName, pair.firstCustomer),
                    second: shown(pair.secondName, pair.secondCustomer),
                  })}
                </p>
                <p className="text-caption text-muted-foreground">
                  {t(`screen.admin.splitResolutions.evidence.signal.${pair.kind}` as MessageKey)}
                </p>
              </li>
            ))}
          </ul>
        ))}
    </RegionState>
  )
}
