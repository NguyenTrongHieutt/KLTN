import { Link } from 'react-router'
import { t, type MessageKey } from '@/i18n/t'
import { formatDayMonth } from '@/lib/dates'
import type { JobSummary } from './jobApi'
import { JobStateBadge } from './JobStateBadge'
import { StageStrip } from './StageStrip'
import { jobGrid, jobTile } from './jobTile'

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
    <ul aria-label={label} className={jobGrid}>
      {jobs.map((job) => (
        <li key={job.id}>
          <Link
            to={job.state === 'DRAFT' ? `/cong-viec/${job.id}/sua` : `/cong-viec/${job.id}`}
            className={jobTile}
          >
            <span className="flex min-w-0 flex-col gap-1">
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
            {/* The state, then where the Job stands among the seven stages, as its card on Trang chủ shows it
                (2026-11-24): a tile no longer holds a title and a badge alone. A Job with no stage (cancelled)
                shows the badge only. */}
            <span className="mt-auto flex flex-col gap-3">
              <span>
                <JobStateBadge state={job.state} />
              </span>
              <StageStrip state={job.state} compact />
            </span>
          </Link>
        </li>
      ))}
    </ul>
  )
}
