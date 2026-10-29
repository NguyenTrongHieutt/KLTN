import { X } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { Link, Navigate, useNavigate, useParams, useSearchParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { TermHint } from '@/components/TermHint'
import { TestTag } from '@/components/TestTag'
import { Button } from '@/components/ui/button'
import { experienceText } from '@/features/identity/personText'
import { VerifiedFactLines } from '@/features/identity/VerifiedFacts'
import { useJob, type Job } from '@/features/job/jobApi'
import { useReputationHistories, type Histories, type ReputationHistory } from '@/features/reputation/historyApi'
import { HistoryRegion, RoleSummaryLine } from '@/features/reputation/RoleHistories'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t } from '@/i18n/t'
import { useMatchMedia } from '@/lib/useMatchMedia'
import { formatVnd } from '@/lib/money'
import { isNotFound } from '@/lib/problemMessage'
import { useJobApplications, type JobApplication } from './applicationApi'
import { ChooseDialog, StaleDialog } from './ChooseBid'
import { comparePath, comparedIds } from './compare'
import { fitRows, profileFacts } from './fitRows'
import { ProofList } from './ProofList'

/** Below this width the columns stack under a sticky header of names (UX-DR101). */
const WIDE = '(min-width: 1024px)'

/** One fixed row: its label, and its cell for one bid; a cell of the histories in short waits for them. */
interface Row {
  key: string
  label: ReactNode
  cell: (application: JobApplication, history: ReputationHistory | undefined) => ReactNode
  history?: boolean
}

/** A row's cell, behind the histories' Skeleton or error when it shows one of them. */
function cellOf(row: Row, application: JobApplication, histories: Histories): ReactNode {
  const login = application.mentor.githubLogin
  const cell = row.cell(application, login ? histories.byUsername.get(login) : undefined)
  return row.history && login ? <HistoryRegion histories={histories}>{cell}</HistoryRegion> : cell
}

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
  const wide = useMatchMedia(WIDE)
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
        {wide ? (
          <div className="overflow-x-auto rounded-lg border border-border bg-card">
            <table className="w-full table-fixed border-collapse text-body-sm">
              <caption className="sr-only">{t('screen.matching.compare.heading', { n: chosen.length })}</caption>
              <thead>
                <tr>
                  <td className="w-56 border-b border-border p-3" />
                  {chosen.map((application) => (
                    <th key={application.id} scope="col" className="border-b border-border p-3 text-left align-top">
                      <Name application={application} onRemove={() => remove(application.id)} />
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {table.map((row) => (
                  <tr key={row.key} className="align-top">
                    <th scope="row" className="border-b border-border p-3 text-left font-normal text-muted-foreground">
                      {row.label}
                    </th>
                    {chosen.map((application) => (
                      <td key={application.id} className="border-b border-border p-3 break-words">
                        {cellOf(row, application, histories)}
                      </td>
                    ))}
                  </tr>
                ))}
                <tr>
                  <td className="p-3" />
                  {chosen.map((application) => (
                    <td key={application.id} className="p-3">
                      <Button type="button" onClick={() => setChoosing(application)}>
                        {t('screen.matching.choose.button')}
                      </Button>
                    </td>
                  ))}
                </tr>
              </tbody>
            </table>
          </div>
        ) : (
          <div className="flex flex-col gap-4">
            <ul
              aria-label={t('screen.matching.compare.heading', { n: chosen.length })}
              className="sticky top-0 z-10 flex flex-wrap gap-x-4 gap-y-1 rounded-md border border-border bg-card px-4 py-2 text-label font-semibold shadow-sm"
            >
              {chosen.map((application) => (
                <li key={application.id} className="break-words">
                  {application.mentor.displayName}
                </li>
              ))}
            </ul>
            {chosen.map((application) => (
              <section
                key={application.id}
                aria-label={application.mentor.displayName}
                className="flex flex-col gap-3 rounded-lg border border-border bg-card p-4"
              >
                <Name application={application} onRemove={() => remove(application.id)} />
                <dl className="flex flex-col gap-3 text-body-sm">
                  {table.map((row) => (
                    <div key={row.key} className="flex flex-col gap-1">
                      <dt className="text-caption text-muted-foreground">{row.label}</dt>
                      <dd className="break-words">{cellOf(row, application, histories)}</dd>
                    </div>
                  ))}
                </dl>
                <Button type="button" className="self-start" onClick={() => setChoosing(application)}>
                  {t('screen.matching.choose.button')}
                </Button>
              </section>
            ))}
          </div>
        )}
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

/** A column's name, its "Đã mời" chip, and "Bỏ khỏi so sánh". */
function Name({ application, onRemove }: { application: JobApplication; onRemove: () => void }) {
  return (
    <span className="flex flex-wrap items-center justify-between gap-2">
      <span className="flex flex-wrap items-center gap-2">
        <span className="text-label font-semibold break-words">{application.mentor.displayName}</span>
        {application.invited && (
          <span className="rounded-full bg-muted px-2 py-0.5 text-caption font-normal">{t('screen.matching.invitedChip')}</span>
        )}
      </span>
      <Button type="button" variant="ghost" size="compact" onClick={onRemove}>
        <X aria-hidden="true" className="size-4" />
        {t('screen.matching.compare.remove')}
      </Button>
    </span>
  )
}
