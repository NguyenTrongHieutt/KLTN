import { Link } from 'react-router'
import { t, type MessageKey } from '@/i18n/t'
import { formatDayMonth } from '@/lib/dates'
import type { JobSummary } from './jobApi'
import { JobStateBadge } from './JobStateBadge'

/**
 * A divided list of the Customer's Jobs (CUS-7's groups, CUS-4's Jobs): the title, a meta line (the Product unless
 * `showProduct` is false, the Job types, the last edit), and the state badge. A Draft links to its form.
 */
export function JobSummaryList({
  jobs,
  showProduct = true,
  label,
}: {
  jobs: JobSummary[]
  showProduct?: boolean
  label?: string
}) {
  return (
    <ul aria-label={label} className="flex flex-col divide-y divide-border rounded-lg border border-border bg-card">
      {jobs.map((job) => (
        <li key={job.id}>
          <Link
            to={job.state === 'DRAFT' ? `/cong-viec/${job.id}/sua` : `/cong-viec/${job.id}`}
            className="flex flex-wrap items-center gap-3 px-4 py-3 text-inherit no-underline hover:bg-muted/50"
          >
            <span className="flex min-w-60 grow flex-col gap-0.5">
              <span className="text-label font-semibold break-words">{job.title}</span>
              <span className="text-caption text-muted-foreground break-words">
                {[
                  showProduct ? job.productName : '',
                  job.jobTypes.map((type) => t(`term.jobType${type}.label` as MessageKey)).join(', '),
                  t('screen.job.editedOn', { date: formatDayMonth(job.updatedAt) }),
                ]
                  .filter((part) => part !== '')
                  .join(' · ')}
              </span>
            </span>
            <JobStateBadge state={job.state} />
          </Link>
        </li>
      ))}
    </ul>
  )
}
