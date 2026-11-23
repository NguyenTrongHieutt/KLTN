import { Link } from 'react-router'
import { buttonVariants } from '@/components/ui/button-variants'
import { ExternalAccessSection } from '@/features/agreement/ExternalAccessSection'
import { hasStarted } from '@/features/job/jobState'
import { t } from '@/i18n/t'
import type { MyJob } from './jobRoleApi'
import { Card } from '@/components/ui/card'
import { StatusBadge } from '@/components/ui/status-badge'

/**
 * A Job the user joined, on CUS-7 "Việc tôi tham gia" (FR-75, FR-66, AD-41, UX-DR86, Story 21.5): its title and
 * Product, the account they give for the Customer's External Access invitations, and the ways into the Job. No share is
 * shown: each Milestone is split after its acceptance (FR-83).
 */
export function CrewJobCard({ job }: { job: MyJob }) {
  return (
    <Card as="article" className="flex flex-col gap-3">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="flex min-w-0 flex-col gap-0.5">
          <h3 className="text-h3 break-words">{job.title}</h3>
          <p className="text-caption text-muted-foreground">{job.productName}</p>
        </div>
        <StatusBadge tone="done">
          {t('screen.crew.student.activeBadge')}
        </StatusBadge>
      </div>
      <ExternalAccessSection jobId={job.jobId} level={3} />
      <div className="flex flex-wrap gap-2">
        {hasStarted(job.state) && (
          <Link to={`/cong-viec/${job.jobId}`} className={buttonVariants({ variant: 'secondary', size: 'compact' })}>
            {t('screen.job.detail.overview')}
          </Link>
        )}
        <Link
          to={`/cong-viec/${job.jobId}/trao-doi-nhom`}
          className={buttonVariants({ variant: 'secondary', size: 'compact' })}
        >
          {t('screen.crewChat.tab')}
        </Link>
      </div>
    </Card>
  )
}
