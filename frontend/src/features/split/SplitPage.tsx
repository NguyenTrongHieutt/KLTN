import { useEffect, useId, useRef, useState } from 'react'
import { Dialog } from '@base-ui/react/dialog'
import { Link, useParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { inputClass } from '@/components/formField-props'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { TestTag } from '@/components/TestTag'
import { Button } from '@/components/ui/button'
import { MentorJobTabs } from '@/features/matching/MentorJobTabs'
import { useMentorJob } from '@/features/matching/mentorJobApi'
import { DeadlineChip } from '@/features/notification/DeadlineChip'
import { TestModeBanner } from '@/features/payment/TestModeBanner'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t } from '@/i18n/t'
import { ApiError } from '@/lib/apiClient'
import { formatDate, formatDayMonth } from '@/lib/dates'
import { formatVnd } from '@/lib/money'
import { isNotFound, problemMessage, sentence } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { ResolutionPanel } from './ResolutionPanel'
import {
  openCase,
  useConfirmSplit,
  useJobSplits,
  useSaveSplit,
  useSplitPreview,
  useSplitResolutions,
  type SplitCard,
  type SplitParty,
  type SplitShare,
  type SplitVersion,
} from './splitApi'

const TONE = {
  waiting: 'bg-warning-soft text-warning',
  progress: 'bg-primary-soft text-primary',
  problem: 'bg-destructive-soft text-destructive',
  done: 'bg-success-soft text-success',
}

/** The Problem Details members a split's refusal carries beside its reason. */
type SplitProblem = ApiError['problem'] & { cap?: number; n?: number; pastDue?: boolean }

/**
 * JOB-10 "Phân chia tiền" (FR-83, FR-84, UX-DR105, UX-DR106): one card per accepted Milestone, newest first, for the
 * Mentor and the split's parties, leavers included, with the Split Resolutions of its Milestone. It stands on the
 * split's own rows, not on the role in the Job, since it stays open for a former member and after the Job is gone.
 * Every amount comes from the server. It reads every 30 s while a split is open. Never reachable by the Customer, whose
 * answer is the not-found screen.
 */
export function SplitPage() {
  const { jobId, milestoneId } = useParams()
  const splits = useJobSplits(jobId, true)
  usePageTitle(t('screen.split.title'))
  if (splits.isError && isNotFound(splits.error)) {
    return <NotFoundScreen />
  }
  const cards = splits.data ?? []
  const mentor = splits.isSuccess && (cards.length === 0 || cards[0].viewerIsMentor)
  return (
    <div className="mx-auto flex max-w-[1120px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <div className="flex flex-col gap-3">
        <Link
          to={cards.length > 0 && !cards[0].jobLive ? '/cong-viec' : `/cong-viec/${jobId ?? ''}`}
          className="w-fit text-label text-muted-foreground underline-offset-4 hover:underline"
        >
          {t('screen.split.back')}
        </Link>
        <PageHeading>{t('screen.split.title')}</PageHeading>
        {cards[0]?.jobTitle && <p className="text-caption text-muted-foreground break-words">{cards[0].jobTitle}</p>}
        {mentor && jobId && <MentorTabs jobId={jobId} />}
      </div>
      <TestModeBanner />
      {/* A failed poll keeps the cards and the Mentor's typed percentages; only a failed first load shows. */}
      <RegionState
        query={{ ...splits, isError: splits.isLoadingError }}
        skeleton={<Skeleton className="h-48 w-full" />}
      >
        {cards.length === 0 ? (
          <p className="text-body-sm text-muted-foreground">{t('screen.split.empty')}</p>
        ) : (
          <div className="flex flex-col gap-4">
            {cards.map((card) => (
              <Card key={card.splitId} jobId={jobId ?? ''} card={card} focused={card.milestoneId === milestoneId} />
            ))}
          </div>
        )}
      </RegionState>
    </div>
  )
}

/** The Mentor's tabs while the Job still has its row; a bare one has no other screen to link to. */
function MentorTabs({ jobId }: { jobId: string }) {
  const job = useMentorJob(jobId)
  if (!job.data) {
    return null
  }
  return <MentorJobTabs jobId={jobId} state={job.data.state} current="split" payment={job.data.agreementConfirmed} />
}

function Card({ jobId, card, focused }: { jobId: string; card: SplitCard; focused: boolean }) {
  const ref = useRef<HTMLElement>(null)
  useEffect(() => {
    if (focused) {
      ref.current?.scrollIntoView?.({ block: 'start' })
    }
  }, [focused])
  const state = stateOf(card)
  return (
    <section
      ref={ref}
      aria-labelledby={`split-${card.splitId}`}
      className={cn(
        'flex flex-col gap-3 rounded-lg border border-border bg-card p-4',
        focused && 'ring-2 ring-primary',
      )}
    >
      <header className="flex flex-wrap items-center justify-between gap-2">
        <h2 id={`split-${card.splitId}`} className="text-h3">
          {t('screen.split.milestone', { n: card.milestoneNumber })}
        </h2>
        <span className={cn('rounded-full px-2.5 py-0.5 text-caption', state.tone)}>{state.text}</span>
      </header>
      <p className="text-body-sm">
        {formatVnd(card.pooledVnd)} <TestTag />{' '}
        {t(card.status === 'FINAL' ? 'screen.split.ofMilestone' : 'screen.split.ofMilestonePending', {
          n: card.milestoneNumber,
        })}
      </p>
      <Chip card={card} />
      <Body jobId={jobId} card={card} />
      <EarlierVersions card={card} />
    </section>
  )
}

/**
 * The chip of the deadline that decides next. A Dispute pauses neither; only a Split Resolution stops the confirmation
 * deadline, and it shows no time left, because a withdrawal gives a full period.
 */
function Chip({ card }: { card: SplitCard }) {
  if (card.status === 'IN_RESOLUTION') {
    return (
      <span className="inline-flex w-fit shrink-0 items-center rounded-full bg-muted px-2.5 py-0.5 text-caption text-muted-foreground">
        {t('screen.split.paused')}
      </span>
    )
  }
  if (card.status === 'AWAITING_DRAFT' && !card.draftLate) {
    return <DeadlineChip deadlineAt={card.draftDueAt} near={card.draftNear} />
  }
  if (card.status === 'PROPOSED' && card.confirmationDueAt && !card.pastDue) {
    return <DeadlineChip deadlineAt={card.confirmationDueAt} near={card.confirmationNear} />
  }
  return null
}

function Body({ jobId, card }: { jobId: string; card: SplitCard }) {
  if (card.finality === 'MENTOR_ONLY') {
    return (
      <>
        <p className="text-body-sm">{t('screen.split.mentorOnly')}</p>
        <Lines card={card} version={card.latest} />
      </>
    )
  }
  if (card.status === 'AWAITING_DRAFT') {
    return card.viewerIsMentor && !card.draftLate ? (
      <>
        <NoMemberNotice card={card} />
        <Draft jobId={jobId} card={card} shownVersionNo={0} />
      </>
    ) : (
      <>
        <NoMemberNotice card={card} />
        {card.draftLate && <p className="text-body-sm">{t('screen.split.draftLate')}</p>}
        <Parties card={card} />
      </>
    )
  }
  return (
    <>
      <NoMemberNotice card={card} />
      <VersionNotices card={card} />
      <Lines card={card} version={card.latest} />
      {card.status === 'PROPOSED' && !card.pastDue && (
        <>
          {card.viewerIsMentor && <MentorActions jobId={jobId} card={card} />}
          {!card.viewerIsMentor && !card.viewerConfirmed && <PartyActions jobId={jobId} card={card} />}
        </>
      )}
      {card.status === 'IN_RESOLUTION' && card.viewerIsMentor && card.latest?.kind === 'DRAFT' && (
        <div className="flex flex-col gap-1">
          <Button type="button" variant="secondary" size="compact" className="w-fit" disabled>
            {t('screen.split.edit')}
          </Button>
          <p className="text-caption text-muted-foreground">{sentence(t('reason.job.SPLIT_IN_RESOLUTION'))}</p>
        </div>
      )}
      <ResolutionPanel jobId={jobId} card={card} />
    </>
  )
}

/** X5, for the Mentor and the leavers, whose rules change when nobody was in the Crew at acceptance. */
function NoMemberNotice({ card }: { card: SplitCard }) {
  return card.capApplies ? null : <p className="text-body-sm">{t('screen.split.noMember')}</p>
}

function VersionNotices({ card }: { card: SplitCard }) {
  const version = card.latest
  if (!version) {
    return null
  }
  const mentorLine = version.lines.find((line) => partyOf(card, line.partyId)?.mentor)
  return (
    <>
      {version.kind === 'DEFAULT' && (
        <p className="text-body-sm">
          {t(version.capPercent === undefined ? 'screen.split.defaultNoCap' : 'screen.split.default', {
            date: formatDayMonth(card.draftDueAt),
            pct: mentorLine?.percent ?? 0,
            cap: version.capPercent ?? 0,
          })}
        </p>
      )}
      {card.status === 'IN_RESOLUTION' && <p className="text-body-sm">{t('screen.split.inResolution')}</p>}
      {card.status === 'FINAL' && (
        <p className="text-body-sm">
          {t('screen.split.final', { date: formatDate(card.finalAt ?? ''), how: howOf(card) })}
        </p>
      )}
      {card.status === 'PROPOSED' &&
        !card.pastDue &&
        !card.viewerIsMentor &&
        !card.viewerConfirmed &&
        version.kind === 'DRAFT' &&
        version.versionNo > 1 && (
          <p className="text-body-sm">
            {t('screen.split.edited', {
              k: card.editsUsed,
              n: card.editLimit,
              date: formatDayMonth(card.confirmationDueAt ?? ''),
            })}
          </p>
        )}
    </>
  )
}

function howOf(card: SplitCard): string {
  const how =
    card.finality === 'CONFIRMED'
      ? t('screen.split.how.confirmed')
      : card.finality === 'DEADLINE'
        ? t('screen.split.how.deadline')
        : t('screen.split.how.decision')
  return card.latest?.kind === 'DEFAULT' ? `${how} · ${t('screen.split.how.default')}` : how
}

/** What the state badge says (EXPERIENCE.md's split states), the tone following who waits on whom. */
function stateOf(card: SplitCard): { text: string; tone: string } {
  if (card.finality === 'MENTOR_ONLY') {
    return { text: t('screen.split.state.mentorOnly'), tone: TONE.done }
  }
  if (card.status === 'FINAL') {
    return { text: t('screen.split.state.final', { date: formatDayMonth(card.finalAt ?? '') }), tone: TONE.done }
  }
  if (card.status === 'IN_RESOLUTION') {
    return { text: t('screen.split.state.inResolution'), tone: TONE.problem }
  }
  if (card.pastDue) {
    return { text: t('screen.split.state.pastDue'), tone: TONE.progress }
  }
  if (card.status === 'AWAITING_DRAFT') {
    const date = formatDayMonth(card.draftDueAt)
    if (card.draftLate) {
      return { text: t('screen.split.state.draftLate'), tone: TONE.progress }
    }
    return card.viewerIsMentor
      ? { text: t('screen.split.state.draftMentor', { date }), tone: TONE.waiting }
      : { text: t('screen.split.state.draftParty', { date }), tone: TONE.progress }
  }
  const date = formatDayMonth(card.confirmationDueAt ?? '')
  const isDefault = card.latest?.kind === 'DEFAULT'
  if (card.viewerIsMentor) {
    return {
      text: t(isDefault ? 'screen.split.state.defaultMentor' : 'screen.split.state.sentMentor', {
        k: card.k,
        n: card.n,
        date,
      }),
      tone: TONE.progress,
    }
  }
  if (card.viewerConfirmed) {
    return { text: t('screen.split.state.confirmed'), tone: TONE.progress }
  }
  return {
    text: t(isDefault ? 'screen.split.state.defaultParty' : 'screen.split.state.waitingParty', { date }),
    tone: TONE.waiting,
  }
}

function partyOf(card: SplitCard, partyId: string): SplitParty | undefined {
  return card.parties.find((party) => party.partyId === partyId)
}

function PartyName({ party }: { party: SplitParty | undefined }) {
  return (
    <>
      <span className="break-words">
        {party?.name || (party?.erased ? t('screen.split.erased') : t('screen.split.noName'))}
      </span>
      {party?.mentor && <span className="text-caption text-muted-foreground"> · {t('screen.split.mentorMark')}</span>}
      {party?.leftAt && (
        <span className="block text-caption text-muted-foreground">
          {t('screen.split.left', { date: formatDayMonth(party.leftAt) })}
        </span>
      )}
    </>
  )
}

/** The parties of a split with no version yet: who it is shared among, leavers marked. */
function Parties({ card }: { card: SplitCard }) {
  return (
    <ul className="flex flex-col gap-1 text-body-sm">
      {card.parties.map((party) => (
        <li key={party.partyId}>
          <PartyName party={party} />
        </li>
      ))}
    </ul>
  )
}

/** Every line of a version (X11), each with its VND from the server, read-only. */
function Lines({ card, version }: { card: SplitCard; version: SplitVersion | undefined }) {
  // Read beside the panel below it, which polls: one query, whose open case says who asked for the review.
  const resolutions = useSplitResolutions(card.splitId, card.status === 'IN_RESOLUTION', false)
  if (!version) {
    return null
  }
  const current = version.versionNo === card.latest?.versionNo
  const raiser = openCase(resolutions.data)?.raiserPartyId
  return (
    <div className="overflow-x-auto">
      <table className="w-full border-collapse text-body-sm">
        <caption className="sr-only">{t('screen.split.linesCaption', { n: version.versionNo })}</caption>
        <thead>
          <tr className="text-left text-caption text-muted-foreground">
            <th scope="col" className="border-b border-border py-2 pr-3 font-normal">
              {t('screen.split.column.party')}
            </th>
            <th scope="col" className="border-b border-border py-2 pr-3 font-normal">
              {t('screen.split.column.percent')}
            </th>
            <th scope="col" className="border-b border-border py-2 pr-3 font-normal">
              {t('screen.split.column.amount')}
            </th>
            {current && (
              <th scope="col" className="border-b border-border py-2 font-normal">
                {t('screen.split.column.status')}
              </th>
            )}
          </tr>
        </thead>
        <tbody>
          {version.lines.map((line) => {
            const party = partyOf(card, line.partyId)
            return (
              <tr key={line.partyId} className="align-top">
                <td className="border-b border-border py-2 pr-3">
                  <PartyName party={party} />
                </td>
                <td className="border-b border-border py-2 pr-3">{line.percent}%</td>
                <td className="border-b border-border py-2 pr-3 whitespace-nowrap">
                  {formatVnd(line.amountVnd)} <TestTag />
                </td>
                {current && (
                  <td className="border-b border-border py-2 text-caption">{lineStatus(card, party, line, raiser)}</td>
                )}
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

function lineStatus(
  card: SplitCard,
  party: SplitParty | undefined,
  line: SplitVersion['lines'][number],
  raiser: string | undefined,
): string | null {
  if (card.status === 'FINAL') {
    return card.paid && line.amountVnd > 0 ? t('screen.split.paid') : null
  }
  if (party?.mentor) {
    return null
  }
  if (line.partyId === raiser) {
    return t('screen.split.resolution.asked')
  }
  return line.confirmed ? t('screen.split.confirmed') : t('screen.split.waiting')
}

/** PRD's version history: every earlier version, read-only and collapsed, for every party to check. */
function EarlierVersions({ card }: { card: SplitCard }) {
  if (card.earlier.length === 0) {
    return null
  }
  return (
    <details className="text-body-sm">
      <summary className="cursor-pointer text-label">{t('screen.split.earlier')}</summary>
      <div className="mt-2 flex flex-col gap-3">
        {card.earlier.map((version) => (
          <div key={version.versionNo} className="flex flex-col gap-1">
            <p className="text-caption text-muted-foreground">
              {t(version.kind === 'DEFAULT' ? 'screen.split.earlierDefault' : 'screen.split.earlierVersion', {
                n: version.versionNo,
                date: formatDate(version.createdAt),
              })}
            </p>
            <Lines card={card} version={version} />
          </div>
        ))}
      </div>
    </details>
  )
}

/** The Mentor's view of a sent split: "Sửa phân chia" while edits remain, never for the default (X4). */
function MentorActions({ jobId, card }: { jobId: string; card: SplitCard }) {
  // The version the form opened on: a poll may bring a newer one, and the save must then answer STALE.
  const [from, setFrom] = useState<SplitVersion | null>(null)
  const version = card.latest
  if (!version || version.kind !== 'DRAFT') {
    return null
  }
  if (from) {
    return (
      <Draft
        key={from.versionNo}
        jobId={jobId}
        card={card}
        shownVersionNo={from.versionNo}
        initial={from}
        onDone={() => setFrom(null)}
        onReloaded={() => setFrom(version)}
      />
    )
  }
  const spent = card.editsLeft === 0
  return (
    <div className="flex flex-col gap-1">
      <Button
        type="button"
        variant="secondary"
        size="compact"
        className="w-fit"
        disabled={spent}
        onClick={() => setFrom(version)}
      >
        {t('screen.split.edit')}
      </Button>
      <p className="text-caption text-muted-foreground">
        {spent
          ? t('screen.split.editsSpent', { n: card.editLimit })
          : t('screen.split.editsLeft', { k: card.editsLeft, n: card.editLimit })}
      </p>
    </div>
  )
}

/** A party's confirmation: N-30 above "Xác nhận"; the panel below the lines offers "Nhờ quản trị viên xem xét". */
function PartyActions({ jobId, card }: { jobId: string; card: SplitCard }) {
  const confirm = useConfirmSplit(jobId, card.splitId)
  const version = card.latest
  const [stale, setStale] = useState(false)
  const [asking, setAsking] = useState(false)
  if (!version) {
    return null
  }
  const date = formatDayMonth(card.confirmationDueAt ?? '')
  return (
    <div className="flex flex-col gap-2">
      <p className="text-body-sm">{t('screen.split.n30', { date })}</p>
      <Button type="button" className="w-fit" disabled={confirm.isPending} onClick={() => setAsking(true)}>
        {t('screen.split.confirm')}
      </Button>
      {confirm.isError && !stale && (
        <p role="alert" className="text-body-sm text-destructive">
          {refusal(confirm.error, card)}
        </p>
      )}
      <ConfirmDialog
        open={asking}
        title={t('screen.split.confirmDialog.title', { n: card.milestoneNumber })}
        body={t('screen.split.confirmDialog.body')}
        confirmLabel={t('screen.split.confirm')}
        pending={confirm.isPending}
        onConfirm={() =>
          confirm.mutate(version.versionNo, {
            onError: (error) => setStale(isStale(error)),
            onSettled: () => setAsking(false),
          })
        }
        onClose={() => setAsking(false)}
      />
      <StaleDialog
        open={stale}
        onClose={() => {
          setStale(false)
          confirm.reset()
        }}
      />
    </div>
  )
}

/** The Mentor's draft: whole percentages with a live sum, the server's preview, N-29 above "Gửi để xác nhận". */
function Draft({
  jobId,
  card,
  shownVersionNo,
  initial,
  onDone,
  onReloaded,
}: {
  jobId: string
  card: SplitCard
  shownVersionNo: number
  initial?: SplitVersion
  onDone?: () => void
  /** The Stale dialog closed: start the form again from the newer version. */
  onReloaded?: () => void
}) {
  const ids = useId()
  const save = useSaveSplit(jobId, card.splitId)
  const [stale, setStale] = useState(false)
  const [asking, setAsking] = useState(false)
  const [typed, setTyped] = useState<Record<string, string>>(() =>
    Object.fromEntries(
      card.parties.map((party) => [
        party.partyId,
        String(initial?.lines.find((line) => line.partyId === party.partyId)?.percent ?? ''),
      ]),
    ),
  )
  const percents = card.parties.map((party) => Number(typed[party.partyId] ?? ''))
  const filled = card.parties.every((party) => (typed[party.partyId] ?? '') !== '')
  const sum = percents.reduce((total, percent) => total + percent, 0)
  const problems = Object.fromEntries(
    card.parties.map((party) => [party.partyId, lineProblem(card, party, typed[party.partyId] ?? '')]),
  )
  const valid = filled && sum === 100 && Object.values(problems).every((problem) => problem === null)
  const shares: SplitShare[] | undefined = valid
    ? card.parties.map((party) => ({ partyId: party.partyId, percent: Number(typed[party.partyId]) }))
    : undefined
  // An unchanged edit would use an edit, void every confirmation and restart the clock for nothing.
  const unchanged = initial?.lines.every((line) => Number(typed[line.partyId]) === line.percent) ?? false
  const preview = useSplitPreview(card.splitId, shares)
  const dialogBody = t('screen.split.sendDialog.body', { date: formatDayMonth(card.nextConfirmationDueAt) })
  return (
    <form
      className="flex flex-col gap-3"
      onSubmit={(event) => {
        event.preventDefault()
        if (shares && !unchanged) {
          setAsking(true)
        }
      }}
    >
      <div className="flex flex-col gap-2">
        {card.parties.map((party) => {
          const problem = problems[party.partyId]
          const amount = preview.data?.lines.find((line) => line.partyId === party.partyId)?.amountVnd
          const hint = `${ids}-${party.partyId}-hint`
          return (
            <div key={party.partyId} className="flex flex-wrap items-center gap-3 border-b border-border py-2">
              <label htmlFor={`${ids}-${party.partyId}`} className="min-w-48 flex-1 text-body-sm">
                <PartyName party={party} />
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
              <span className="min-w-32 text-body-sm whitespace-nowrap">
                {amount !== undefined && valid && (
                  <>
                    {formatVnd(amount)} <TestTag />
                  </>
                )}
              </span>
              <span
                id={hint}
                className={cn('w-full text-caption', problem !== null ? 'text-destructive' : 'text-muted-foreground')}
              >
                {problem ??
                  (party.mentor && card.cap !== undefined ? t('screen.split.capHint', { cap: card.cap }) : null)}
              </span>
            </div>
          )
        })}
      </div>
      <p
        className={cn('text-label', filled && sum !== 100 ? 'text-destructive' : 'text-muted-foreground')}
        aria-live="polite"
      >
        {t('screen.split.sum', { sum })}
      </p>
      <p className="text-caption text-muted-foreground">
        {t('screen.split.editsLeft', { k: card.editsLeft, n: card.editLimit })}
      </p>
      <p className="text-body-sm">{n29(card)}</p>
      {save.isError && !stale && (
        <p role="alert" className="text-body-sm text-destructive">
          {refusal(save.error, card)}
        </p>
      )}
      {preview.isError && (
        <p role="alert" className="text-body-sm text-destructive">
          {refusal(preview.error, card)}
        </p>
      )}
      <div className="flex flex-wrap gap-2">
        <Button type="submit" disabled={!valid || unchanged || save.isPending}>
          {t('screen.split.send')}
        </Button>
        {onDone && (
          <Button type="button" variant="ghost" onClick={onDone}>
            {t('screen.split.cancelEdit')}
          </Button>
        )}
      </div>
      <ConfirmDialog
        open={asking}
        title={t('screen.split.sendDialog.title')}
        body={initial ? `${t('screen.split.sendDialog.edit')} ${dialogBody}` : dialogBody}
        confirmLabel={t('screen.split.send')}
        pending={save.isPending}
        onConfirm={() => {
          if (shares) {
            save.mutate(
              { shownVersionNo, shares },
              {
                onSuccess: () => onDone?.(),
                onError: (error) => setStale(isStale(error)),
                onSettled: () => setAsking(false),
              },
            )
          }
        }}
        onClose={() => setAsking(false)}
      />
      <StaleDialog
        open={stale}
        onClose={() => {
          setStale(false)
          save.reset()
          onReloaded?.()
        }}
      />
    </form>
  )
}

/** A line's broken rule, which turns it red and disables the send; the server checks again on every save. */
function lineProblem(card: SplitCard, party: SplitParty, text: string): string | null {
  if (text === '') {
    return null
  }
  const percent = Number(text)
  if (percent > 100) {
    return t('screen.split.lineTooMuch')
  }
  if (party.mentor && card.cap !== undefined && percent > card.cap) {
    return t('screen.split.lineOverCap', { cap: card.cap })
  }
  if (!party.mentor && card.capApplies && party.wholeInterval && percent === 0) {
    return t('screen.split.lineZero')
  }
  return null
}

/** N-29, with the sentence of X5 in place of the cap's when nobody was in the Crew at acceptance. */
function n29(card: SplitCard): string {
  const values = {
    date: formatDayMonth(card.nextConfirmationDueAt),
    k: card.editsLeft,
    n: card.editLimit,
    cap: card.cap ?? 0,
  }
  return card.cap === undefined ? t('screen.split.n29NoCap', values) : t('screen.split.n29', values)
}

function isStale(error: unknown): boolean {
  return error instanceof ApiError && error.problem.code === 'STALE'
}

/** A refusal in plain words (AD-23): the split's reasons name the figures the server sends beside them. */
function refusal(error: unknown, card: SplitCard): string {
  if (error instanceof ApiError) {
    const problem: SplitProblem = error.problem
    switch (problem.reason) {
      case 'SPLIT_INVALID':
        return sentence(
          problem.cap === undefined
            ? t('reason.job.SPLIT_INVALID_NO_CAP')
            : t('reason.job.SPLIT_INVALID', { cap: problem.cap }),
        )
      case 'SPLIT_EDIT_LIMIT':
        return sentence(t('reason.job.SPLIT_EDIT_LIMIT', { n: problem.n ?? card.editLimit }))
      case 'SPLIT_FINAL':
        return sentence(problem.pastDue ? t('reason.job.SPLIT_FINAL_PAST_DUE') : t('reason.job.SPLIT_FINAL'))
      default:
        break
    }
  }
  return problemMessage(error, { module: 'job' }).text
}

/** The Stale dialog (UX-DR26): the command was not sent; the card has already reloaded, so decide again. */
function StaleDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  return (
    <Dialog.Root open={open} onOpenChange={(next) => !next && onClose()}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
        <Dialog.Popup className="fixed top-24 left-1/2 z-50 flex w-[min(480px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
          <Dialog.Title className="text-h2">{t('error.STALE')}</Dialog.Title>
          <Dialog.Description className="text-body-sm">{t('screen.split.staleBody')}</Dialog.Description>
          <div className="flex justify-end">
            <Button type="button" onClick={onClose}>
              {t('screen.split.staleReload')}
            </Button>
          </div>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
