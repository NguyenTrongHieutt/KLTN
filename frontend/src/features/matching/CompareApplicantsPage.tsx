import { useState } from 'react'
import { Link, Navigate, useNavigate, useParams, useSearchParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { TermHint } from '@/components/TermHint'
import { VerifiedFactLines } from '@/features/identity/VerifiedFacts'
import { useReputationHistories } from '@/features/reputation/historyApi'
import { RoleSummaryLine } from '@/features/reputation/RoleHistories'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t } from '@/i18n/t'
import { formatDayMonth } from '@/lib/dates'
import { isNotFound } from '@/lib/problemMessage'
import { compareApplicantsPath, comparedIds } from './compare'
import { ComparisonView, type ComparisonRow } from './Comparison'
import { useJobCrewApplications, useRecruitment, type ApplicantCard } from './crewApplicationApi'
import { fitRows, profileFacts } from './fitRows'
import { PickDialog } from './PickDialogs'
import { ProofList } from './ProofList'
import { SkillChips } from './SkillChips'

type Row = ComparisonRow<ApplicantCard>

/**
 * The applicants' fixed rows (UX-DR101): Verified facts; what each posted (the note and the Skills); proof; history as
 * Mentor; history as Crew member; then one row per Fit metric against the Skills sought, with the profile facts beside
 * them. No row marks a best value, and there is no total, rank or sort.
 */
function rows(): Row[] {
  const fixed: Row[] = [
    {
      key: 'facts',
      label: t('screen.matching.compare.rowFacts'),
      cell: (application) => <VerifiedFactLines facts={application.applicant.verified} compact />,
    },
    {
      key: 'posted',
      label: t('screen.recruitment.comparison.rowPosted'),
      cell: (application) => (
        <span className="flex flex-col gap-1.5">
          <span className="break-words whitespace-pre-line">{application.note}</span>
          {application.skills.length > 0 ? (
            <SkillChips names={application.skills.map((skill) => skill.name)} />
          ) : (
            <span className="text-muted-foreground">{t('screen.recruitment.noSkills')}</span>
          )}
        </span>
      ),
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
    cell: (application) => fitRows(application.fitMetrics ?? undefined)[index].value,
  }))
  const profile: Row = {
    key: 'profile',
    label: t('screen.matching.compare.rowProfile'),
    cell: (application, history) => (
      <span className="flex flex-col gap-0.5">
        {profileFacts(history, application.applicant.memberSince ?? undefined).map((fact) => (
          <span key={fact}>{fact}</span>
        ))}
      </span>
    ),
    history: true,
  }
  return [...fixed, ...metrics, profile]
}

/**
 * JOB-9's "So sánh" (`/cong-viec/{jobId}/tuyen-thanh-vien/so-sanh?ids=…`, FR-81, UX-DR101, Story 21.5): two or three
 * of the Job's open Crew Applications side by side in the order chosen, through CUS-13's component, with "Chọn" under
 * each one still pending and "Bỏ khỏi so sánh"; fewer than two go back to JOB-9. The Mentor's alone: anyone else, the
 * Customer included, gets the not-found screen.
 */
export function CompareApplicantsPage() {
  const { jobId } = useParams()
  const recruitment = useRecruitment(jobId)
  usePageTitle(t('screen.recruitment.comparison.title'))
  if (recruitment.isError && isNotFound(recruitment.error)) {
    return <NotFoundScreen />
  }
  return (
    <div className="mx-auto flex max-w-[1280px] flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <RegionState query={recruitment} skeleton={<Skeleton className="h-96 w-full" />}>
        {recruitment.data && jobId && <Applicants jobId={jobId} />}
      </RegionState>
    </div>
  )
}

function Applicants({ jobId }: { jobId: string }) {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const applications = useJobCrewApplications(jobId)
  const open = (applications.data ?? []).filter((item) => item.status === 'PENDING' || item.status === 'PICKED')
  const ids = comparedIds(
    params.get('ids'),
    open.map((application) => application.id),
  )
  const chosen = ids
    .map((id) => open.find((application) => application.id === id))
    .filter((application): application is ApplicantCard => application !== undefined)
  const histories = useReputationHistories(
    chosen.map((application) => application.applicant.githubLogin).filter((login): login is string => !!login),
  )
  const [picking, setPicking] = useState<ApplicantCard>()
  const back = `/cong-viec/${jobId}/tuyen-thanh-vien`
  if (applications.isSuccess && chosen.length < 2) {
    return <Navigate to={back} replace />
  }
  const remove = (id: string) => {
    const left = chosen.map((application) => application.id).filter((other) => other !== id)
    void navigate(left.length < 2 ? back : compareApplicantsPath(jobId, left), { replace: true })
  }
  const heading = t('screen.recruitment.comparison.heading', { n: chosen.length })
  return (
    <>
      <div className="flex flex-col gap-2">
        <Link to={back} className="w-fit text-body-sm text-primary underline-offset-4 hover:underline">
          {t('screen.recruitment.comparison.back')}
        </Link>
        <PageHeading>{heading}</PageHeading>
        <p className="text-body-sm text-muted-foreground">{t('screen.matching.compare.order')}</p>
      </div>
      <RegionState query={applications} skeleton={<Skeleton className="h-96 w-full" />}>
        <ComparisonView
          heading={heading}
          items={chosen}
          rows={rows()}
          name={(application) => application.applicant.displayName}
          extra={(application) =>
            application.status === 'PICKED' &&
            application.signDueAt && (
              <span className="rounded-full bg-muted px-2 py-0.5 text-caption font-normal">
                {t('screen.recruitment.pickedChip', { date: formatDayMonth(application.signDueAt) })}
              </span>
            )
          }
          login={(application) => application.applicant.githubLogin}
          histories={histories}
          chooseLabel={t('screen.recruitment.pick')}
          canChoose={(application) => application.status === 'PENDING'}
          onChoose={setPicking}
          onRemove={(application) => remove(application.id)}
        />
      </RegionState>
      <p className="text-caption text-muted-foreground">{t('screen.matching.fit.note')}</p>
      {picking && <PickDialog jobId={jobId} application={picking} onClose={() => setPicking(undefined)} />}
    </>
  )
}
