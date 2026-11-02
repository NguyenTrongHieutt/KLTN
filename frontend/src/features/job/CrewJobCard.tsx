import { Link } from 'react-router'
import { buttonVariants } from '@/components/ui/button-variants'
import { ExternalAccessSection } from '@/features/agreement/ExternalAccessSection'
import { hasBoard } from '@/features/work/workApi'
import { t } from '@/i18n/t'
import type { MyJob } from './jobRoleApi'

/**
 * A Job the user joined, on CUS-7 "Việc tôi tham gia" (FR-75, FR-66, AD-41, UX-DR86, Story 21.5): its title and
 * Product, the account they give for the Customer's External Access invitations, and the ways into the Job. No share is
 * shown: each Milestone is split after its acceptance (FR-83).
 */
export function CrewJobCard({ job }: { job: MyJob }) {
  return (
    <article className="flex flex-col gap-3 rounded-lg border border-border bg-card p-5">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="flex min-w-0 flex-col gap-0.5">
          <h3 className="text-h3 break-words">{job.title}</h3>
          <p className="text-caption text-muted-foreground">{job.productName}</p>
        </div>
        <span className="rounded-full bg-success-soft px-2 py-0.5 text-caption font-semibold text-success">
          {t('screen.crew.student.activeBadge')}
        </span>
      </div>
      <ExternalAccessSection jobId={job.jobId} level={3} />
      <div className="flex flex-wrap gap-2">
        {hasBoard(job.state) && (
          <>
            <Link to={`/cong-viec/${job.jobId}`} className={buttonVariants({ variant: 'secondary', size: 'compact' })}>
              {t('screen.job.detail.overview')}
            </Link>
            <Link to={`/cong-viec/${job.jobId}/bang`} className={buttonVariants({ variant: 'secondary', size: 'compact' })}>
              {t('screen.board.tab')}
            </Link>
            <Link to={`/cong-viec/${job.jobId}/tai-lieu`} className={buttonVariants({ variant: 'secondary', size: 'compact' })}>
              {t('screen.docs.tab')}
            </Link>
          </>
        )}
        <Link
          to={`/cong-viec/${job.jobId}/trao-doi-nhom`}
          className={buttonVariants({ variant: 'secondary', size: 'compact' })}
        >
          {t('screen.crewChat.tab')}
        </Link>
      </div>
    </article>
  )
}
