import { useState } from 'react'
import { Link, Navigate, useNavigate, useParams, useSearchParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { TermHint } from '@/components/TermHint'
import { TestTag } from '@/components/TestTag'
import { experienceText } from '@/features/identity/personText'
import { VerifiedFactLines } from '@/features/identity/VerifiedFacts'
import { useJob, type Job } from '@/features/job/jobApi'
import { useReputationHistories } from '@/features/reputation/historyApi'
import { RoleSummaryLine } from '@/features/reputation/RoleHistories'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t } from '@/i18n/t'
import { formatVnd } from '@/lib/money'
import { isNotFound } from '@/lib/problemMessage'
import { useJobApplications, type JobApplication } from './applicationApi'
import { ChooseDialog, StaleDialog } from './ChooseBid'
import { comparePath, comparedIds } from './compare'
import { ComparisonView, type ComparisonRow } from './Comparison'
import { fitRows, profileFacts } from './fitRows'
import { ProofList } from './ProofList'

type Row = ComparisonRow<JobApplication>

/**
 * The fixed rows of CUS-13 (UX-DR101): Verified facts; what each posted (price, timeline, approach); proof; history as
 * Mentor; history as Crew member; then one row per Fit metric, with the profile facts beside them. No row marks a best
 * value, and there is no total, rank or sort.
 */
function rows(): Row[] {
  const fixed: Row[] = [
    {
      key: 'facts',
      label: t('screen.matching.compare.rowFacts'),
      cell: (application) => <VerifiedFactLines facts={application.mentor.verified} compact />,
    },
    {
      key: 'posted',
      label: t('screen.matching.compare.rowPosted'),
      cell: (application) => (
        <span className="flex flex-col gap-0.5">
          <span>
            <span className="font-semibold">{formatVnd(application.priceVnd)}</span> <TestTag />
          </span>
          <span>{t('screen.matching.days', { n: application.timelineDays })}</span>
          <span className="text-muted-foreground">
            {experienceText(application.mentor.yearsOfExperience)} ({t('screen.person.selfDeclared')})
          </span>
        </span>
      ),
    },
    {
      key: 'approach',
      label: t('screen.matching.compare.rowApproach'),
      cell: (application) => <span className="break-words whitespace-pre-line">{application.approach}</span>,
    },
    {
      key: 'proof',
      label: t('screen.matching.compare.rowProof'),
      cell: (application) => <ProofList proof={application.proof} />,
    },
    {
      key: 'mentor',
      label: t('screen.matching.compare.rowMentor'),
      cell: (_, history) => <RoleSummaryLine role="mentor" summary={history?.mentor} newcomer={history?.newcomer ?? false} />,
      history: true,
    },
    {
      key: 'crew',
      label: t('screen.matching.compare.rowCrew'),
      cell: (_, history) => <RoleSummaryLine role="crew" summary={history?.crew} newcomer={false} />,
      history: true,
    },
  ]
  const metrics: Row[] = fitRows(undefined).map((row, index) => ({
    key: row.term,
    label: <TermHint term={row.term} />,
    cell: (application) => fitRows(application.fitMetrics)[index].value,
  }))
  const profile: Row = {
    key: 'profile',
    label: t('screen.matching.compare.rowProfile'),
    cell: (application, history) => (
      <span className="flex flex-col gap-0.5">
        {profileFacts(history, application.mentor.memberSince).map((fact) => (
          <span key={fact}>{fact}</span>
        ))}
      </span>
    ),
    history: true,
  }
  return [...fixed, ...metrics, profile]
}

/**
 * CUS-13 "So sánh người chào giá" (`/cong-viec/{jobId}/ung-tuyen/so-sanh?ids=…`, FR-15, UX-DR101, Story 21.3): two or
 * three bids side by side in the order chosen, row by row in a fixed order, with "Chọn" under each column and "Bỏ khỏi
 * so sánh"; fewer than two of the Job's open bids go back to CUS-8.
 */
export function CompareBidsPage() {
  const { jobId } = useParams()
  const job = useJob(jobId)
  usePageTitle(job.data ? t('screen.matching.compare.title', { job: job.data.title }) : t('screen.job.title'))
  if (job.isError && isNotFound(job.error)) {
    return <NotFoundScreen />
  }
  if (job.data && job.data.state !== 'OPEN') {
    return <Navigate to={`/cong-viec/${job.data.id}`} replace />
  }
  return (
    <div className="mx-auto flex max-w-[1280px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <RegionState query={job} skeleton={<Skeleton className="h-96 w-full" />}>
        {job.data && <Comparison job={job.data} />}
      </RegionState>
    </div>
  )
}

function Comparison({ job }: { job: Job }) {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const applications = useJobApplications(job.id)
  const all = applications.data ?? []
  const ids = comparedIds(
    params.get('ids'),
    all.map((application) => application.id),
  )
  const chosen = ids
    .map((id) => all.find((application) => application.id === id))
    .filter((application): application is JobApplication => application !== undefined)
  const histories = useReputationHistories(
    chosen.map((application) => application.mentor.githubLogin).filter((login): login is string => !!login),
  )
  const [choosing, setChoosing] = useState<JobApplication>()
  const [stale, setStale] = useState(false)
  const back = `/cong-viec/${job.id}/ung-tuyen`
  if (applications.isSuccess && chosen.length < 2) {
    return <Navigate to={back} replace />
  }
  const remove = (id: string) => {
    const left = chosen.map((application) => application.id).filter((other) => other !== id)
    void navigate(left.length < 2 ? back : comparePath(job.id, left), { replace: true })
  }
  const table = rows()
  return (
    <>
      <div className="flex flex-col gap-2">
        <Link to={back} className="w-fit text-body-sm text-primary underline-offset-4 hover:underline">
          {t('screen.matching.compare.back')}
        </Link>
        <PageHeading>{t('screen.matching.compare.heading', { n: chosen.length })}</PageHeading>
        <p className="text-body-sm text-muted-foreground">{t('screen.matching.compare.order')}</p>
      </div>
      <RegionState query={applications} skeleton={<Skeleton className="h-96 w-full" />}>
        <ComparisonView
          heading={t('screen.matching.compare.heading', { n: chosen.length })}
          items={chosen}
          rows={table}
          name={(application) => application.mentor.displayName}
          extra={(application) =>
            application.invited && (
              <span className="rounded-full bg-muted px-2 py-0.5 text-caption font-normal">{t('screen.matching.invitedChip')}</span>
            )
          }
          login={(application) => application.mentor.githubLogin}
          histories={histories}
          chooseLabel={t('screen.matching.choose.button')}
          onChoose={setChoosing}
          onRemove={(application) => remove(application.id)}
        />
      </RegionState>
      <p className="text-caption text-muted-foreground">{t('screen.matching.fit.note')}</p>
      {choosing && (
        <ChooseDialog
          job={job}
          application={choosing}
          others={all.length - 1}
          onClose={() => setChoosing(undefined)}
          onStale={() => {
            setChoosing(undefined)
            setStale(true)
          }}
        />
      )}
      <StaleDialog open={stale} onClose={() => setStale(false)} jobId={job.id} />
    </>
  )
}
