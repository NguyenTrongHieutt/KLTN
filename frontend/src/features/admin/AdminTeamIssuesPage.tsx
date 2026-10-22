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
import { crewSplit } from '@/features/crew/crewShares'
import { t, type MessageKey } from '@/i18n/t'
import { formatDayMonthTime } from '@/lib/dates'
import { formatVnd } from '@/lib/money'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { CaseTabs } from './CaseTabs'
import {
  useAdminTeamIssue,
  useAdminTeamIssues,
  useCrewChatEvidence,
  useCrewTermsEvidence,
  useTeamIssueCommand,
  useWorkEvidence,
  type AdminTeamIssue,
} from './adminTeamIssuesApi'
import { StepUpDialog } from './StepUpDialog'

/**
 * ADM-4's "Khiếu nại nội bộ" tab (FR-63, FR-79, AD-20, AD-40, UX-DR84): the escalated Team Issues, the
 * undecided first; one with "Nhận xử lý", the Team Evidence Package (Crew Terms versions, the Crew Chat, the
 * raiser's Work Items, the Rating), each read under the assignee's grant and audited by its module, and the
 * decision: whole percentage points from the Mentor's share with the VND parts previewed, or keeping or
 * removing the Rating, with written reasoning and the step-up. Decisions are final.
 */
export function AdminTeamIssuesPage() {
  const { teamIssueId } = useParams()
  usePageTitle(t('screen.admin.teamIssues.title'))
  return (
    <div className="flex flex-col gap-5">
      <PageHeading>{t('screen.admin.disputes.title')}</PageHeading>
      <CaseTabs current="teamIssues" />
      <div className="grid gap-5 lg:grid-cols-[minmax(0,320px)_minmax(0,1fr)]">
        <IssueList current={teamIssueId} />
        {teamIssueId ? (
          <IssueDetail key={teamIssueId} teamIssueId={teamIssueId} />
        ) : (
          <p className="text-body-sm text-muted-foreground">{t('screen.admin.teamIssues.pick')}</p>
        )}
      </div>
    </div>
  )
}

function subjectText(subject: string, n: number | null | undefined) {
  return subject === 'SHARE'
    ? t('screen.admin.teamIssues.subject.SHARE', { n: n ?? 0 })
    : t('screen.admin.teamIssues.subject.RATING')
}

function IssueList({ current }: { current?: string }) {
  const issues = useAdminTeamIssues()
  return (
    <RegionState query={issues} skeleton={<Skeleton className="h-64 w-full" />}>
      {issues.data &&
        (issues.data.length === 0 ? (
          <p className="text-body-sm text-muted-foreground">{t('screen.admin.teamIssues.empty')}</p>
        ) : (
          <ul className="flex flex-col gap-2">
            {issues.data.map((row) => (
              <li key={row.id}>
                <Link
                  to={`/quan-tri/khieu-nai-noi-bo/${row.id}`}
                  aria-current={row.id === current ? 'page' : undefined}
                  className={cn(
                    'flex flex-col gap-0.5 rounded-md border border-border bg-card p-3 text-body-sm',
                    row.id === current && 'border-primary',
                  )}
                >
                  <span className="text-label font-semibold break-words">{row.jobTitle}</span>
                  <span className="text-caption text-muted-foreground">
                    {[
                      subjectText(row.subject, row.milestoneNumber),
                      formatDayMonthTime(row.escalatedAt),
                      t(row.phase === 'ESCALATED' ? 'screen.admin.teamIssues.waiting' : 'screen.admin.teamIssues.decided'),
                      row.assignedToMe
                        ? t('screen.admin.teamIssues.assignedToMe')
                        : row.assigned
                          ? t('screen.admin.teamIssues.assigned')
                          : '',
                    ]
                      .filter((part) => part !== '')
                      .join(' · ')}
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        ))}
    </RegionState>
  )
}

function IssueDetail({ teamIssueId }: { teamIssueId: string }) {
  const issue = useAdminTeamIssue(teamIssueId)
  return (
    <RegionState query={issue} skeleton={<Skeleton className="h-96 w-full" />}>
      {issue.data && <Detail data={issue.data} />}
    </RegionState>
  )
}

function Detail({ data }: { data: AdminTeamIssue }) {
  const toasts = useToasts()
  const command = useTeamIssueCommand(data.id)
  const [error, setError] = useState<string>()
  const fail = (failure: unknown) => setError(problemMessage(failure, { module: 'dispute' }).text)
  const open = data.phase === 'ESCALATED'
  return (
    <section className="flex flex-col gap-4 rounded-lg border border-border bg-card p-5 text-body-sm">
      <h2 className="text-h2 break-words">{data.jobTitle}</h2>
      <p className="text-caption text-muted-foreground">
        {subjectText(data.subject, data.milestoneNumber)} ·{' '}
        {t('screen.admin.teamIssues.people', { raiser: data.raiserName ?? '', mentor: data.mentorName ?? '' })} ·{' '}
        {t('screen.admin.teamIssues.escalatedAt', { time: formatDayMonthTime(data.escalatedAt) })}
      </p>
      <div className="flex flex-col gap-1">
        <span className="text-label">{t('screen.admin.teamIssues.reason')}</span>
        <p className="break-words whitespace-pre-line">{data.reason}</p>
      </div>
      <p>
        {data.answerKind == null
          ? t('screen.admin.teamIssues.answer.none')
          : t(`screen.admin.teamIssues.answer.${data.answerKind}` as MessageKey, { n: data.answerPoints ?? 0 })}
      </p>
      {error && (
        <p role="alert" className="text-destructive">
          {error}
        </p>
      )}
      {data.decision ? (
        <Outcome data={data} />
      ) : open && !data.assignedToMe ? (
        <div className="flex flex-col gap-2">
          <p className="text-caption text-muted-foreground">{t('screen.admin.teamIssues.takeNote')}</p>
          <div>
            <Button
              type="button"
              disabled={command.isPending}
              onClick={() => command.mutate({ action: 'take' }, { onSuccess: () => setError(undefined), onError: fail })}
            >
              {t('screen.admin.teamIssues.take')}
            </Button>
          </div>
        </div>
      ) : (
        open && (
          <DecisionForm
            data={data}
            pending={command.isPending}
            onDecide={(body, refused) =>
              command.mutate(
                { action: 'decide', body },
                {
                  onSuccess: () => toasts.success(t('screen.admin.teamIssues.decision.decided')),
                  onError: refused,
                },
              )
            }
          />
        )
      )}
      {data.assignedToMe && <Evidence data={data} />}
    </section>
  )
}

function Outcome({ data }: { data: AdminTeamIssue }) {
  const decision = data.decision
  if (!decision) {
    return null
  }
  const outcome =
    data.subject === 'SHARE'
      ? decision.points
        ? t('screen.admin.teamIssues.decision.outcomePoints', { n: decision.points })
        : t('screen.admin.teamIssues.decision.outcomeNone')
      : t(decision.ratingRemoved ? 'screen.admin.teamIssues.decision.outcomeRemoved' : 'screen.admin.teamIssues.decision.outcomeKept')
  return (
    <div className="flex flex-col gap-1 rounded-md bg-muted p-3">
      <p className="font-semibold">{outcome}</p>
      <p className="break-words whitespace-pre-line">{decision.reasoning}</p>
      <p className="text-caption text-muted-foreground">
        {t('screen.admin.teamIssues.decision.at', { time: formatDayMonthTime(decision.decidedAt) })}
      </p>
    </div>
  )
}

type Body = { points?: number; removeRating?: boolean; reasoning: string; stepUpCode: string }

function DecisionForm({
  data,
  pending,
  onDecide,
}: {
  data: AdminTeamIssue
  pending: boolean
  onDecide: (body: Body, refused: (error: unknown) => void) => void
}) {
  const ids = useId()
  const share = data.subject === 'SHARE'
  const split = data.split
  const cap = split?.capPoints ?? 0
  const [pointsText, setPointsText] = useState('0')
  const [remove, setRemove] = useState(false)
  const [reasoning, setReasoning] = useState('')
  const [stepUp, setStepUp] = useState(false)
  const points = /^\d{1,3}$/.test(pointsText.trim()) ? Number(pointsText.trim()) : undefined
  const over = share && points !== undefined && points > cap
  const ready = reasoning.trim() !== '' && (!share || (points !== undefined && !over))
  let preview: string | undefined
  if (share && split && points !== undefined && !over) {
    const shares = new Map<string, number>([
      ['mentor', split.mentorPercent - points],
      ['raiser', split.raiserPercent + points],
    ])
    split.others.forEach((percent, index) => shares.set(`other-${index}`, percent))
    const parts = crewSplit(split.basisVnd, shares, 'mentor')
    preview = t('screen.admin.teamIssues.decision.preview', {
      amount: formatVnd(split.basisVnd),
      mentorPct: split.mentorPercent - points,
      mentorVnd: formatVnd(parts.get('mentor') ?? 0),
      raiserPct: split.raiserPercent + points,
      raiserVnd: formatVnd(parts.get('raiser') ?? 0),
    })
  }
  return (
    <fieldset className="flex flex-col gap-3 rounded-md border border-border bg-background p-3">
      <legend className="px-1 text-label">{t('screen.admin.teamIssues.decision.title')}</legend>
      {share ? (
        <>
          <label htmlFor={`${ids}-points`} className="text-label">
            {t('screen.admin.teamIssues.decision.points')}
          </label>
          <input
            id={`${ids}-points`}
            inputMode="numeric"
            value={pointsText}
            onChange={(event) => setPointsText(event.target.value.replace(/[^0-9]/g, ''))}
            className={inputClass}
            aria-describedby={`${ids}-cap`}
          />
          <p id={`${ids}-cap`} className={cn('text-caption', over ? 'text-destructive' : 'text-muted-foreground')}>
            {over
              ? t('screen.teamIssue.answer.overCap', { cap })
              : t('screen.admin.teamIssues.decision.cap', { cap, pct: split?.mentorPercent ?? 0 })}
          </p>
          {preview && (
            <p className="text-caption">
              {preview} <TestTag />
            </p>
          )}
        </>
      ) : (
        <div className="flex flex-wrap gap-x-4">
          {[false, true].map((option) => (
            <label key={String(option)} className="flex min-h-tap-min items-center gap-2">
              <input
                type="radio"
                name={`${ids}-rating`}
                className="size-4"
                checked={remove === option}
                onChange={() => setRemove(option)}
              />
              {t(option ? 'screen.admin.teamIssues.decision.remove' : 'screen.admin.teamIssues.decision.keep')}
            </label>
          ))}
        </div>
      )}
      <label htmlFor={`${ids}-reasoning`} className="text-label">
        {t('screen.admin.teamIssues.decision.reasoning')}
      </label>
      <textarea
        id={`${ids}-reasoning`}
        value={reasoning}
        maxLength={4000}
        onChange={(event) => setReasoning(event.target.value)}
        className={cn(inputClass, 'min-h-24')}
      />
      <p className="text-caption text-muted-foreground">{t('screen.admin.teamIssues.decision.final')}</p>
      <div className="flex justify-end">
        <Button type="button" variant="destructive" disabled={!ready || pending} onClick={() => setStepUp(true)}>
          {t('screen.admin.teamIssues.decision.confirm')}
        </Button>
      </div>
      <StepUpDialog
        open={stepUp}
        onOpenChange={setStepUp}
        confirmLabel={t('screen.admin.teamIssues.decision.confirm')}
        pending={pending}
        onConfirm={(code, refused) =>
          onDecide(
            share
              ? { points: points ?? 0, reasoning: reasoning.trim(), stepUpCode: code }
              : { removeRating: remove, reasoning: reasoning.trim(), stepUpCode: code },
            refused,
          )
        }
      />
    </fieldset>
  )
}

type EvidenceTab = 'terms' | 'chat' | 'work' | 'rating'

/** The Team Evidence Package, each part read from its module under the grant (AD-40, UX-DR84). */
function Evidence({ data }: { data: AdminTeamIssue }) {
  const tabs: EvidenceTab[] = data.subject === 'RATING' ? ['terms', 'chat', 'work', 'rating'] : ['terms', 'chat', 'work']
  const [tab, setTab] = useState<EvidenceTab>('terms')
  return (
    <section aria-labelledby="team-evidence" className="flex flex-col gap-3">
      <h3 id="team-evidence" className="text-h3">
        {t('screen.admin.teamIssues.evidence.title')}
      </h3>
      <div role="tablist" aria-label={t('screen.admin.teamIssues.evidence.title')} className="flex flex-wrap gap-2">
        {tabs.map((option) => (
          <button
            key={option}
            type="button"
            role="tab"
            aria-selected={tab === option}
            onClick={() => setTab(option)}
            className={cn(
              'min-h-control-compact rounded-full border px-3 text-label focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring',
              tab === option ? 'border-primary bg-primary-soft text-foreground' : 'border-border bg-card text-muted-foreground hover:bg-muted',
            )}
          >
            {t(`screen.admin.teamIssues.evidence.${option}` as MessageKey)}
          </button>
        ))}
      </div>
      <div role="tabpanel">
        {tab === 'terms' && <TermsEvidence jobId={data.jobId} />}
        {tab === 'chat' && <ChatEvidence jobId={data.jobId} />}
        {tab === 'work' && <WorkEvidence jobId={data.jobId} />}
        {tab === 'rating' && data.rating && (
          <div className="flex flex-col gap-1 rounded-md bg-muted p-3">
            <p className="font-semibold">{t('screen.admin.teamIssues.evidence.stars', { n: data.rating.stars })}</p>
            <p className="break-words whitespace-pre-line">{data.rating.comment}</p>
            {data.rating.hiddenAt && (
              <p className="text-caption text-muted-foreground">{t('screen.admin.teamIssues.evidence.hidden')}</p>
            )}
          </div>
        )}
      </div>
    </section>
  )
}

function TermsEvidence({ jobId }: { jobId: string }) {
  const terms = useCrewTermsEvidence(jobId, true)
  return (
    <RegionState query={terms} skeleton={<Skeleton className="h-24 w-full" />}>
      <ul className="flex flex-col gap-2">
        {terms.data?.versions.map((version) => (
          <li key={version.versionNo} className="flex flex-col gap-0.5 rounded-md bg-muted p-3">
            <p className="font-semibold">
              {t('screen.admin.teamIssues.evidence.version', {
                n: version.versionNo,
                status: t(`screen.admin.teamIssues.evidence.status.${version.status}` as MessageKey),
              })}
            </p>
            {version.shares.map((share) => (
              <p key={`${version.versionNo}-${share.name}`} className="text-caption">
                {t('screen.admin.teamIssues.evidence.share', { name: share.name, pct: share.percent })}
                {share.confirmedAt
                  ? ` · ${t('screen.admin.teamIssues.evidence.confirmed', { time: formatDayMonthTime(share.confirmedAt) })}`
                  : ''}
              </p>
            ))}
          </li>
        ))}
      </ul>
    </RegionState>
  )
}

function ChatEvidence({ jobId }: { jobId: string }) {
  const chat = useCrewChatEvidence(jobId, true)
  return (
    <RegionState query={chat} skeleton={<Skeleton className="h-24 w-full" />}>
      {chat.data &&
        (chat.data.items.length === 0 ? (
          <p className="text-muted-foreground">{t('screen.admin.teamIssues.evidence.noMessages')}</p>
        ) : (
          <ul className="flex flex-col gap-2">
            {chat.data.items.map((message) => (
              <li key={message.id} className="flex flex-col gap-0.5 rounded-md bg-muted p-3">
                <p className="text-caption text-muted-foreground">
                  {message.author} · {formatDayMonthTime(message.sentAt)}
                </p>
                <p className="break-words whitespace-pre-line">{message.body}</p>
              </li>
            ))}
          </ul>
        ))}
    </RegionState>
  )
}

function WorkEvidence({ jobId }: { jobId: string }) {
  const board = useWorkEvidence(jobId, true)
  return (
    <RegionState query={board} skeleton={<Skeleton className="h-24 w-full" />}>
      {board.data &&
        (board.data.items.length === 0 ? (
          <p className="text-muted-foreground">{t('screen.admin.teamIssues.evidence.noItems')}</p>
        ) : (
          <ul className="flex flex-col gap-2">
            {board.data.items.map((item) => (
              <li key={item.id} className="rounded-md bg-muted p-3">
                <span className="font-semibold">{item.key}</span> <span className="break-words">{item.title}</span>
                {item.assignee && <span className="text-caption text-muted-foreground"> · {item.assignee.name}</span>}
              </li>
            ))}
          </ul>
        ))}
    </RegionState>
  )
}
