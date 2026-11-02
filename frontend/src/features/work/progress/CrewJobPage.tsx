import { Link } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { buttonVariants } from '@/components/ui/button-variants'
import { useCrewMe, type CrewMe } from '@/features/crew/crewApi'
import { LeaveJob } from '@/features/crew/LeaveJob'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t } from '@/i18n/t'
import { isNotFound } from '@/lib/problemMessage'
import { JobRoleLine } from '@/features/job/ByJobRole'
import { ClosureSection, DeletionChecklist } from '@/features/job/closure/ClosureSection'
import { useDataDeletion } from '@/features/job/closure/closureApi'
import { SetupTracker } from '@/features/job/SetupTracker'
import { useJobSplits } from '@/features/split/splitApi'
import { JobPortfolioSection } from '@/features/reputation/JobPortfolioSection'
import { RatingSection } from '@/features/reputation/RatingSection'
import { hasBoard } from '@/features/work/workApi'
import { ProgressOverview } from './ProgressOverview'

/**
 * JOB-1 for a Crew member (FR-77, UX-DR68, UX-DR87, Story 21.6), in every state from their signature on, so before the
 * Board too: the Job's title and Product, "Bạn là: Thành viên nhóm", the ways to its Board, Documentation Space, and
 * Crew Chat, "Rời công việc" until Closing, the step tracker while the Job is Starting (UX-DR23), and the progress
 * overview with their own Work Items and N-12. A former member, one whose lock counted as leaving included, reads
 * JOB-H's banner and gets their post-Job duties instead, with no way to the handovers (AD-41). "Phân chia tiền"
 * (JOB-10) shows for a party of a split, active or former: only the party's own splits answer (Story 22.2). Anyone else,
 * a pick not yet signed included, sees the not-found screen.
 */
export function CrewJobPage({ jobId }: { jobId: string }) {
  const crew = useCrewMe(jobId)
  usePageTitle(crew.data?.title ?? t('screen.job.title'))
  if (crew.isError && isNotFound(crew.error)) {
    return <NotFoundScreen />
  }
  return (
    <div className="mx-auto flex max-w-[1120px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <RegionState query={crew} skeleton={<Skeleton className="h-24 w-full" />}>
        {crew.data && <Content me={crew.data} />}
      </RegionState>
    </div>
  )
}

function Content({ me }: { me: CrewMe }) {
  const jobId = me.jobId
  const board = hasBoard(me.state)
  // `job` keeps no record of J7: a Job cancelled before it has no Board, and these links then answer not-found.
  const boardLinks = board || me.state === 'CANCELLED'
  const tab = buttonVariants({ variant: 'secondary', size: 'compact' })
  const splits = useJobSplits(jobId)
  return (
    <>
      <div className="flex flex-col gap-3">
        <Link to="/cong-viec" className="w-fit text-label text-muted-foreground underline-offset-4 hover:underline">
          {t('screen.crewChat.back')}
        </Link>
        <PageHeading>{me.title}</PageHeading>
        <p className="text-caption text-muted-foreground break-words">{me.productName}</p>
        <JobRoleLine jobId={jobId} />
        <div className="flex flex-wrap items-center justify-between gap-3">
          <nav aria-label={t('screen.job.detail.tabs')} className="flex flex-wrap gap-2">
            {boardLinks && (
              <>
                <Link to={`/cong-viec/${jobId}/bang`} className={tab}>
                  {t('screen.board.tab')}
                </Link>
                <Link to={`/cong-viec/${jobId}/tai-lieu`} className={tab}>
                  {t('screen.docs.tab')}
                </Link>
                {!me.former && (
                  <Link to={`/cong-viec/${jobId}/ban-giao`} className={tab}>
                    {t('screen.handover.tab')}
                  </Link>
                )}
              </>
            )}
            <Link to={`/cong-viec/${jobId}/trao-doi-nhom`} className={tab}>
              {t('screen.crewChat.tab')}
            </Link>
            {splits.data && splits.data.length > 0 && (
              <Link to={`/cong-viec/${jobId}/phan-chia`} className={tab}>
                {t('screen.split.tab')}
              </Link>
            )}
          </nav>
          {me.canLeave && <LeaveJob me={me} />}
        </div>
      </div>
      {me.former ? (
        <FormerDuties jobId={jobId} state={me.state} />
      ) : (
        <>
          {me.state === 'STARTING' && <SetupTracker jobId={jobId} />}
          {board ? (
            <ProgressOverview jobId={jobId} audience="crew" />
          ) : (
            <p className="text-body-sm text-muted-foreground">{t('screen.job.detail.notYet')}</p>
          )}
        </>
      )}
    </>
  )
}

/**
 * What stays for a member the Crew no longer counts (AD-41, UX spine "Crew member former"), in every state: their
 * data-deletion Checklist, which the closure section holds once the Job is Closing, and once the Job is Closed, private
 * feedback and the Portfolio summary.
 */
function FormerDuties({ jobId, state }: { jobId: string; state: string }) {
  const deletion = useDataDeletion(jobId).data
  const closure = state === 'CLOSING' || state === 'CLOSED'
  return (
    <div className="flex flex-col gap-6">
      {closure ? (
        <ClosureSection jobId={jobId} />
      ) : (
        deletion?.mine && <DeletionChecklist jobId={jobId} deletion={deletion} />
      )}
      {state === 'CLOSED' && <RatingSection jobId={jobId} />}
      {state === 'CLOSED' && <JobPortfolioSection jobId={jobId} />}
    </div>
  )
}
