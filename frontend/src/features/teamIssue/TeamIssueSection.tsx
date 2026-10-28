import { useId, useState, type ReactNode } from 'react'
import { useToasts } from '@/components/toasts'
import { TestTag } from '@/components/TestTag'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { formatDayMonth } from '@/lib/dates'
import { formatVnd } from '@/lib/money'
import { problemMessage } from '@/lib/problemMessage'
import {
  useChooseTeamIssue,
  useRaiseTeamIssue,
  useTeamIssuePanel,
  type TeamIssue,
  type TeamIssuePanel,
  type TeamIssueRatingOption,
  type TeamIssueShareOption,
} from './teamIssueApi'

/**
 * "Khiếu nại nội bộ" in "Việc tôi tham gia" on CUS-7 (FR-79, AD-40, UX-DR63): on each Job where the member was
 * ever active, they choose "Phần chia giai đoạn {n}" while that Milestone is Held, or "Điểm người hướng dẫn chấm tôi" within
 * 14 days of it becoming visible, write the reason, and send it to the Mentor; an option already used is
 * disabled with its reason. Their issues show with the period they are in; in the escalation period the
 * Mentor's answer shows with its preview, then "Chấp nhận" or "Nhờ quản trị viên xem xét". Nothing shows
 * without an option or an issue.
 */
export function TeamIssueSection() {
  const panel = useTeamIssuePanel()
  if (!panel.data || panel.data.length === 0) {
    return null
  }
  return (
    <section aria-labelledby="team-issue-title" className="flex flex-col gap-2.5">
      <h2 id="team-issue-title" className="text-h2">
        {t('screen.teamIssue.title')}
      </h2>
      <p className="text-body-sm text-muted-foreground">{t('screen.teamIssue.lead')}</p>
      {panel.data.map((job) => (
        <JobCard key={job.jobId} job={job} />
      ))}
    </section>
  )
}

type Choice = { subject: 'SHARE'; option: TeamIssueShareOption } | { subject: 'RATING'; option: TeamIssueRatingOption }

function JobCard({ job }: { job: TeamIssuePanel }) {
  const ids = useId()
  const toasts = useToasts()
  const raise = useRaiseTeamIssue()
  const [chosen, setChosen] = useState<string>()
  const [reason, setReason] = useState('')
  const choices: Choice[] = [
    ...job.shares.map((option) => ({ subject: 'SHARE' as const, option })),
    ...job.ratings.map((option) => ({ subject: 'RATING' as const, option })),
  ]
  const key = (choice: Choice) => (choice.subject === 'SHARE' ? choice.option.milestoneId : choice.option.ratingId)
  const choice = choices.find((found) => key(found) === chosen && found.option.refusal == null)
  const ready = choice !== undefined && reason.trim().length > 0 && reason.length <= 2000

  const submit = () => {
    if (!choice) {
      return
    }
    raise.mutate(
      {
        jobId: job.jobId,
        subject: choice.subject,
        milestoneId: choice.subject === 'SHARE' ? choice.option.milestoneId : undefined,
        ratingId: choice.subject === 'RATING' ? choice.option.ratingId : undefined,
        reason: reason.trim(),
      },
      {
        onSuccess: () => {
          setChosen(undefined)
          setReason('')
          toasts.success(t('screen.teamIssue.sent'))
        },
        onError: (error) => toasts.error(problemMessage(error, { module: 'dispute' }).text),
      },
    )
  }

  return (
    <article aria-labelledby={`${ids}-title`} className="flex flex-col gap-3 rounded-lg border border-border bg-card p-5">
      <div className="flex flex-col gap-0.5">
        <h3 id={`${ids}-title`} className="text-h3 break-words">
          {job.title}
        </h3>
        <p className="text-caption text-muted-foreground">{t('screen.teamIssue.mentor', { name: job.mentorName })}</p>
      </div>
      {job.issues.length > 0 && (
        <ul className="flex flex-col gap-2">
          {job.issues.map((issue) => (
            <IssueLine key={issue.id} issue={issue} audience="crew">
              {issue.phase === 'ESCALATION' && <Choice issue={issue} mentorName={job.mentorName} />}
            </IssueLine>
          ))}
        </ul>
      )}
      {choices.length > 0 && (
        <fieldset className="flex flex-col gap-2">
          <legend className="text-label font-semibold">{t('screen.teamIssue.choose')}</legend>
          {choices.map((option) => (
            <label key={key(option)} className="flex items-start gap-2 text-body-sm">
              <input
                type="radio"
                name={`${ids}-subject`}
                className="mt-1"
                value={key(option)}
                checked={chosen === key(option)}
                disabled={option.option.refusal != null || raise.isPending}
                onChange={() => setChosen(key(option))}
              />
              <span className="flex flex-col gap-0.5">
                <span>
                  {option.subject === 'SHARE'
                    ? t('screen.teamIssue.share', { n: option.option.number })
                    : t('screen.teamIssue.rating')}
                </span>
                {option.subject === 'RATING' && option.option.refusal == null && (
                  <span className="text-caption text-muted-foreground">
                    {t('screen.teamIssue.ratingLine', { n: option.option.stars, date: formatDayMonth(option.option.closesAt) })}
                  </span>
                )}
                {option.option.refusal != null && (
                  <span className="text-caption text-muted-foreground">{refusalText(option)}</span>
                )}
              </span>
            </label>
          ))}
          <div className="flex flex-col gap-1 text-body-sm">
            <label htmlFor={`${ids}-reason`} className="text-label font-semibold">
              {t('screen.teamIssue.reason')}
            </label>
            <textarea
              id={`${ids}-reason`}
              className="min-h-24 rounded-md border border-border bg-background p-2"
              value={reason}
              maxLength={2000}
              disabled={raise.isPending}
              aria-describedby={`${ids}-hint`}
              onChange={(event) => setReason(event.target.value)}
            />
            <span id={`${ids}-hint`} className="text-caption text-muted-foreground">
              {t('screen.teamIssue.reasonHint')}
            </span>
          </div>
          <div>
            <Button type="button" onClick={submit} disabled={!ready || raise.isPending}>
              {t('screen.teamIssue.submit')}
            </Button>
          </div>
        </fieldset>
      )}
    </article>
  )
}

function refusalText(choice: Choice) {
  const { refusal, endedAt } = choice.option
  if (refusal === 'TEAM_ISSUE_ALREADY_RAISED' && endedAt) {
    return t(choice.subject === 'SHARE' ? 'screen.teamIssue.raisedShare' : 'screen.teamIssue.raisedRating', {
      date: formatDayMonth(endedAt),
    })
  }
  return refusal === 'TEAM_ISSUE_ALREADY_RAISED' ? t('reason.dispute.TEAM_ISSUE_ALREADY_RAISED') : t('reason.dispute.TEAM_ISSUE_OPEN')
}

/** The raiser's choice in the escalation period: the answer and its preview, then accept or escalate. */
function Choice({ issue, mentorName }: { issue: TeamIssue; mentorName: string }) {
  const toasts = useToasts()
  const choose = useChooseTeamIssue()
  const preview = issue.answerPreview
  const due = issue.escalationDueAt ? formatDayMonth(issue.escalationDueAt) : ''
  const run = (choice: 'accept' | 'escalate') =>
    choose.mutate(
      { issueId: issue.id, choice },
      {
        onSuccess: () => toasts.success(t(choice === 'accept' ? 'screen.teamIssue.choice.accepted' : 'screen.teamIssue.choice.escalated')),
        onError: (error) => toasts.error(problemMessage(error, { module: 'dispute' }).text),
      },
    )
  return (
    <div className="flex flex-col gap-2 border-t border-border pt-2">
      {issue.answerKind == null && <p>{t('screen.teamIssue.choice.noAnswer')}</p>}
      {issue.answerKind === 'TRANSFER' && preview && issue.split && (
        <p>
          {t('screen.teamIssue.choice.preview', {
            amount: formatVnd(issue.split.basisVnd),
            n: issue.milestoneNumber ?? 0,
            raiserPct: preview.raiserPercent,
            raiserVnd: formatVnd(preview.raiserVnd),
            mentor: mentorName,
            mentorPct: preview.mentorPercent,
            mentorVnd: formatVnd(preview.mentorVnd),
          })}{' '}
          <TestTag />
        </p>
      )}
      <div className="flex flex-wrap gap-2">
        {issue.answerKind != null && (
          <Button type="button" size="compact" disabled={choose.isPending} onClick={() => run('accept')}>
            {t('screen.teamIssue.choice.accept')}
          </Button>
        )}
        <Button type="button" size="compact" variant="secondary" disabled={choose.isPending} onClick={() => run('escalate')}>
          {t('screen.teamIssue.choice.escalate')}
        </Button>
      </div>
      <p className="text-caption text-muted-foreground">
        {t(issue.answerKind == null ? 'screen.teamIssue.choice.lapseSilent' : 'screen.teamIssue.choice.lapse', { date: due })}
      </p>
    </div>
  )
}

/** One Team Issue with the period it is in, its answer, and its outcome, for its raiser or for the Mentor. */
export function IssueLine({
  issue,
  audience,
  children,
}: {
  issue: TeamIssue
  audience: 'crew' | 'mentor'
  children?: ReactNode
}) {
  const subject =
    issue.subject === 'SHARE'
      ? t('screen.teamIssue.share', { n: issue.milestoneNumber ?? 0 })
      : t('screen.teamIssue.rating')
  const answer = answerText(issue)
  const outcome = outcomeText(issue)
  return (
    <li className="flex flex-col gap-1 rounded-md bg-muted p-3 text-body-sm">
      <p className="font-semibold break-words">
        {audience === 'mentor' ? t('screen.teamIssue.from', { name: issue.raiserName ?? '', subject }) : subject}
      </p>
      <p className="break-words whitespace-pre-line">{issue.reason}</p>
      {answer && <p>{answer}</p>}
      <p className="text-caption text-muted-foreground">{phaseText(issue, audience)}</p>
      {outcome && <p className="text-caption text-muted-foreground">{outcome}</p>}
      {issue.subject === 'SHARE' && issue.phase !== 'ENDED' && (
        <p className="text-caption text-muted-foreground">{t('screen.teamIssue.held')}</p>
      )}
      {children}
    </li>
  )
}

function answerText(issue: TeamIssue) {
  switch (issue.answerKind) {
    case 'KEEP':
      return t(issue.subject === 'SHARE' ? 'screen.teamIssue.answerText.keepShare' : 'screen.teamIssue.answerText.keepRating')
    case 'TRANSFER':
      return t('screen.teamIssue.answerText.transfer', { n: issue.answerPoints ?? 0 })
    case 'WITHDRAW_RATING':
      return t('screen.teamIssue.answerText.withdraw')
    default:
      return undefined
  }
}

function outcomeText(issue: TeamIssue) {
  if (issue.phase !== 'ENDED' || !issue.ending) {
    return undefined
  }
  const ending = t(`screen.teamIssue.ending.${issue.ending as 'ACCEPTED' | 'LAPSED' | 'DECIDED' | 'MOOT'}`)
  const outcome =
    issue.subject === 'SHARE'
      ? issue.outcomePoints
        ? t('screen.teamIssue.outcome.points', { n: issue.outcomePoints })
        : t('screen.teamIssue.outcome.none')
      : t(issue.outcomeRatingRemoved ? 'screen.teamIssue.outcome.removed' : 'screen.teamIssue.outcome.kept')
  return `${ending} · ${outcome}`
}

function phaseText(issue: TeamIssue, audience: 'crew' | 'mentor') {
  switch (issue.phase) {
    case 'ANSWER':
      return t(audience === 'mentor' ? 'screen.teamIssue.phase.answerMentor' : 'screen.teamIssue.phase.answer', {
        date: formatDayMonth(issue.answerDueAt),
      })
    case 'ESCALATION':
      return t('screen.teamIssue.phase.escalation', { date: issue.escalationDueAt ? formatDayMonth(issue.escalationDueAt) : '' })
    case 'ESCALATED':
      return t('screen.teamIssue.phase.escalated')
    default:
      return t('screen.teamIssue.phase.ended', { date: issue.endedAt ? formatDayMonth(issue.endedAt) : '' })
  }
}
