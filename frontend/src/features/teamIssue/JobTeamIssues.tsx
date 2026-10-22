import { t } from '@/i18n/t'
import { IssueLine } from './TeamIssueSection'
import { useJobTeamIssues } from './teamIssueApi'

/**
 * JOB-1's Team Issues for the Job's Mentor (FR-79, AD-40, UX-DR63): each issue a Crew member raised, with
 * its reason and the answer period. Nothing shows without one; the Customer's JOB-1 never renders it.
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
          <IssueLine key={issue.id} issue={issue} audience="mentor" />
        ))}
      </ul>
    </section>
  )
}
