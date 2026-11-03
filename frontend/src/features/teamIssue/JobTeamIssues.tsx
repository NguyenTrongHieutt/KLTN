import { useId, useState } from 'react'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { problemMessage } from '@/lib/problemMessage'
import { IssueLine } from './TeamIssueSection'
import { useAnswerTeamIssue, useJobTeamIssues, type TeamIssue } from './teamIssueApi'

/**
 * JOB-1's Team Issues for the Job's Mentor (FR-79, AD-40, UX-DR63): each issue a Crew member raised about the Rating
 * the Mentor gave them, with its reason and the answer period, and the one answer the Mentor gives: "Giữ nguyên điểm"
 * or "Rút lại điểm". Nothing shows without an issue; the Customer's JOB-1 never renders it.
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

type Kind = 'KEEP' | 'WITHDRAW_RATING'

function AnswerForm({ jobId, issue }: { jobId: string; issue: TeamIssue }) {
  const ids = useId()
  const toasts = useToasts()
  const answer = useAnswerTeamIssue(jobId)
  const [kind, setKind] = useState<Kind>('KEEP')
  const [confirming, setConfirming] = useState(false)
  const name = issue.raiserName ?? ''
  const choices: { value: Kind; label: string }[] = [
    { value: 'KEEP', label: t('screen.teamIssue.answer.keepRating') },
    { value: 'WITHDRAW_RATING', label: t('screen.teamIssue.answer.withdraw') },
  ]

  const send = () =>
    answer.mutate(
      { issueId: issue.id, body: { kind } },
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
      <p className="text-caption text-muted-foreground">{t('screen.teamIssue.answer.once', { name })}</p>
      <div>
        <Button type="button" size="compact" onClick={() => setConfirming(true)} disabled={answer.isPending}>
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
