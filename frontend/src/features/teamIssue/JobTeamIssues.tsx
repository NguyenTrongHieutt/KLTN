import { useId, useState } from 'react'
import { useToasts } from '@/components/toasts'
import { TestTag } from '@/components/TestTag'
import { Button } from '@/components/ui/button'
import { crewSplit } from '@/features/crew/crewShares'
import { ConfirmDialog } from '@/features/team/ConfirmDialog'
import { t } from '@/i18n/t'
import { formatVnd } from '@/lib/money'
import { problemMessage } from '@/lib/problemMessage'
import { IssueLine } from './TeamIssueSection'
import { useAnswerTeamIssue, useJobTeamIssues, type TeamIssue } from './teamIssueApi'

/**
 * JOB-1's Team Issues for the Job's Mentor (FR-79, AD-40, UX-DR63): each issue a Crew member raised, with
 * its reason and the answer period, and the one answer the Mentor gives: "Giữ nguyên", a transfer of whole
 * percentage points from their pinned share with the VND parts previewed from the same split, or "Rút lại
 * điểm" for a Rating. Nothing shows without an issue; the Customer's JOB-1 never renders it.
 */
export function JobTeamIssues({ jobId }: { jobId: string }) {
  const issues = useJobTeamIssues(jobId)
  if (!issues.data || !issues.data.mentor || issues.data.issues.length === 0) {
    return null
  }
  return (
    <section aria-labelledby="job-team-issues" className="flex flex-col gap-2.5 rounded-lg border border-border bg-card p-5">
      <h2 id="job-team-issues" className="text-h2">
        {t('screen.teamIssue.title')}
      </h2>
      <p className="text-body-sm text-muted-foreground">{t('screen.teamIssue.jobLead')}</p>
      <ul className="flex flex-col gap-2">
        {issues.data.issues.map((issue) => (
          <IssueLine key={issue.id} issue={issue} audience="mentor">
            {issue.phase === 'ANSWER' && issue.answerKind == null && <AnswerForm jobId={jobId} issue={issue} />}
          </IssueLine>
        ))}
      </ul>
    </section>
  )
}

type Kind = 'KEEP' | 'TRANSFER' | 'WITHDRAW_RATING'

function AnswerForm({ jobId, issue }: { jobId: string; issue: TeamIssue }) {
  const ids = useId()
  const toasts = useToasts()
  const answer = useAnswerTeamIssue(jobId)
  const [kind, setKind] = useState<Kind>('KEEP')
  const [pointsText, setPointsText] = useState('')
  const [confirming, setConfirming] = useState(false)
  const share = issue.subject === 'SHARE'
  const split = issue.split
  const cap = split?.capPoints ?? 0
  const name = issue.raiserName ?? ''
  const points = /^\d{1,3}$/.test(pointsText.trim()) ? Number(pointsText.trim()) : undefined
  const over = kind === 'TRANSFER' && points !== undefined && points > cap
  const ready = kind !== 'TRANSFER' || (points !== undefined && points >= 1 && !over)
  const choices: { value: Kind; label: string }[] = share
    ? [
        { value: 'KEEP', label: t('screen.teamIssue.answer.keepShare') },
        { value: 'TRANSFER', label: t('screen.teamIssue.answer.transfer', { name }) },
      ]
    : [
        { value: 'KEEP', label: t('screen.teamIssue.answer.keepRating') },
        { value: 'WITHDRAW_RATING', label: t('screen.teamIssue.answer.withdraw') },
      ]

  const send = () =>
    answer.mutate(
      { issueId: issue.id, body: { kind, points: kind === 'TRANSFER' ? points : undefined } },
      {
        onSuccess: () => {
          setConfirming(false)
          toasts.success(t('screen.teamIssue.answer.sent'))
        },
        onError: (error) => {
          setConfirming(false)
          toasts.error(problemMessage(error, { module: 'dispute' }).text)
        },
      },
    )

  return (
    <fieldset className="flex flex-col gap-2 border-t border-border pt-2">
      <legend className="sr-only">{t('screen.teamIssue.answer.submit')}</legend>
      {choices.map((choice) => (
        <label key={choice.value} className="flex items-start gap-2">
          <input
            type="radio"
            name={`${ids}-kind`}
            className="mt-1"
            checked={kind === choice.value}
            disabled={answer.isPending}
            onChange={() => setKind(choice.value)}
          />
          <span>{choice.label}</span>
        </label>
      ))}
      {kind === 'TRANSFER' && split && (
        <div className="flex flex-col gap-1">
          <label htmlFor={`${ids}-points`} className="text-label font-semibold">
            {t('screen.teamIssue.answer.points')}
          </label>
          <input
            id={`${ids}-points`}
            inputMode="numeric"
            className="w-24 rounded-md border border-border bg-background px-2 py-1"
            value={pointsText}
            aria-describedby={`${ids}-cap`}
            onChange={(event) => setPointsText(event.target.value)}
          />
          <p id={`${ids}-cap`} className={over ? 'text-caption text-destructive' : 'text-caption text-muted-foreground'}>
            {over
              ? t('screen.teamIssue.answer.overCap', { cap })
              : t('screen.teamIssue.answer.cap', { cap, n: issue.milestoneNumber ?? 0, pct: split.mentorPercent })}
          </p>
          {points !== undefined && points >= 1 && !over && <Preview issue={issue} points={points} />}
        </div>
      )}
      <p className="text-caption text-muted-foreground">{t('screen.teamIssue.answer.once', { name })}</p>
      <div>
        <Button type="button" size="compact" onClick={() => setConfirming(true)} disabled={!ready || answer.isPending}>
          {t('screen.teamIssue.answer.submit')}
        </Button>
      </div>
      <ConfirmDialog
        open={confirming}
        title={t('screen.teamIssue.answer.confirmTitle')}
        body={t('screen.teamIssue.answer.once', { name })}
        confirmLabel={t('screen.teamIssue.answer.submit')}
        pending={answer.isPending}
        onConfirm={send}
        onClose={() => setConfirming(false)}
      />
    </fieldset>
  )
}

/** The two parties' parts once `points` move, from the same split the server makes (AD-33). */
function Preview({ issue, points }: { issue: TeamIssue; points: number }) {
  const split = issue.split
  if (!split) {
    return null
  }
  const shares = new Map<string, number>([
    ['mentor', split.mentorPercent - points],
    ['raiser', split.raiserPercent + points],
  ])
  split.others.forEach((percent, index) => shares.set(`other-${index}`, percent))
  const parts = crewSplit(split.basisVnd, shares, 'mentor')
  return (
    <p className="text-caption">
      {t('screen.teamIssue.answer.preview', {
        amount: formatVnd(split.basisVnd),
        n: issue.milestoneNumber ?? 0,
        mentorPct: split.mentorPercent - points,
        mentorVnd: formatVnd(parts.get('mentor') ?? 0),
        name: issue.raiserName ?? '',
        raiserPct: split.raiserPercent + points,
        raiserVnd: formatVnd(parts.get('raiser') ?? 0),
      })}{' '}
      <TestTag />
    </p>
  )
}
