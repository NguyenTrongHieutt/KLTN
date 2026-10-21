import { Link } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { buttonVariants } from '@/components/ui/button-variants'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t } from '@/i18n/t'
import { isNotFound } from '@/lib/problemMessage'
import { SetupTracker } from '@/features/job/SetupTracker'
import { useJobProgress } from './progressApi'
import { ProgressOverview } from './ProgressOverview'

/**
 * JOB-1 for an active Crew member (UX-DR68): the Job's title and Product, the ways to its Board,
 * Documentation Space, and Crew Chat, the step tracker while the Job is Starting (UX-DR23), and the
 * progress overview with their own Work Items and N-12.
 * Anyone the progress read does not name sees the not-found screen.
 */
export function CrewJobPage({ jobId }: { jobId: string }) {
  const progress = useJobProgress(jobId)
  usePageTitle(progress.data?.title ?? t('screen.job.title'))
  if (progress.isError && isNotFound(progress.error)) {
    return <NotFoundScreen />
  }
  return (
    <div className="mx-auto flex max-w-[1120px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <RegionState query={progress} skeleton={<Skeleton className="h-24 w-full" />}>
        {progress.data && (
          <div className="flex flex-col gap-3">
            <Link to="/cong-viec" className="w-fit text-label text-muted-foreground underline-offset-4 hover:underline">
              {t('screen.crewChat.back')}
            </Link>
            <PageHeading>{progress.data.title}</PageHeading>
            <p className="text-caption text-muted-foreground break-words">{progress.data.productName}</p>
            <nav aria-label={t('screen.job.detail.tabs')} className="flex flex-wrap gap-2">
              <Link to={`/cong-viec/${jobId}/bang`} className={buttonVariants({ variant: 'secondary', size: 'compact' })}>
                {t('screen.board.tab')}
              </Link>
              <Link to={`/cong-viec/${jobId}/tai-lieu`} className={buttonVariants({ variant: 'secondary', size: 'compact' })}>
                {t('screen.docs.tab')}
              </Link>
              <Link to={`/cong-viec/${jobId}/ban-giao`} className={buttonVariants({ variant: 'secondary', size: 'compact' })}>
                {t('screen.handover.tab')}
              </Link>
              <Link
                to={`/cong-viec/${jobId}/trao-doi-nhom`}
                className={buttonVariants({ variant: 'secondary', size: 'compact' })}
              >
                {t('screen.crewChat.tab')}
              </Link>
            </nav>
          </div>
        )}
      </RegionState>
      {progress.data?.state === 'STARTING' && <SetupTracker jobId={jobId} />}
      {progress.data && <ProgressOverview jobId={jobId} audience="crew" />}
    </div>
  )
}
