import { CircleCheck, Info } from 'lucide-react'
import { useId, useState } from 'react'
import { Link } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { inputClass } from '@/components/formField-props'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { Card } from '@/components/ui/card'
import { Page } from '@/components/ui/page'
import { JobRoleLine } from '@/features/job/ByJobRole'
import { jobHeaderCard } from '@/features/job/jobFrame'
import type { CrewKind } from '@/features/job/jobRoleApi'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t } from '@/i18n/t'
import { formatDayMonth, formatDayMonthTime } from '@/lib/dates'
import { isNotFound } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { AgreementContentView } from './AgreementContent'
import { useCrewAgreement, type CrewAgreement as CrewAgreementData, type CrewAgreementOption } from './agreementApi'

/**
 * JOB-6 read-only for the Crew (FR-75, FR-81, AD-41, UX-DR125, Story 25.1b): a picked applicant, coming from "Đọc thỏa
 * thuận" in the Commitment panel, and an active member read the Agreement they sign for and work under. Until the
 * Agreement is confirmed that is the latest draft the Mentor shared with the Customer; then every confirmed version.
 * Never a comment, the quote, or a Change Request in progress, and nothing to act on. A member who left, and anyone
 * else, sees the not-found screen, as the API answers.
 */
export function CrewAgreement({ jobId, kind }: { jobId: string; kind?: CrewKind }) {
  const [pick, setPick] = useState<CrewAgreementOption>()
  const agreement = useCrewAgreement(jobId, pick)
  usePageTitle(agreement.data ? t('screen.agreement.title', { job: agreement.data.jobTitle }) : t('screen.agreement.tab'))
  if (agreement.isError && isNotFound(agreement.error)) {
    return <NotFoundScreen />
  }
  // A pick not yet signed has no Job page: the way back is the Job Post, where the Commitment panel is (MEN-3).
  const picked = kind === 'ASSIGNED'
  return (
    <Page>
      <div className={jobHeaderCard}>
        <Link
          to={picked ? `/tim-viec/${jobId}` : `/cong-viec/${jobId}`}
          className="w-fit text-label text-muted-foreground underline-offset-4 hover:underline"
        >
          {t(picked ? 'screen.agreement.crew.backPost' : 'screen.agreement.crew.backJob')}
        </Link>
        <PageHeading>{agreement.data?.jobTitle ?? t('screen.agreement.tab')}</PageHeading>
        <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
          <p className="text-caption text-muted-foreground">
            {t(picked ? 'screen.agreement.crew.readOnlyPicked' : 'screen.agreement.crew.readOnly')}
          </p>
          {!picked && <JobRoleLine jobId={jobId} />}
        </div>
      </div>
      <RegionState query={agreement} skeleton={<Skeleton className="h-96 w-full" />}>
        {agreement.data && (
          <Versions agreement={agreement.data} pick={pick} loading={agreement.isPlaceholderData} onPick={setPick} />
        )}
      </RegionState>
    </Page>
  )
}

function Versions({
  agreement,
  pick,
  loading,
  onPick,
}: {
  agreement: CrewAgreementData
  /** The version picked in the selector, which may still be loading. */
  pick?: CrewAgreementOption
  /** The picked version is loading, and the one before it is still shown. */
  loading: boolean
  onPick: (option: CrewAgreementOption) => void
}) {
  const ids = useId()
  const { shown, content } = agreement
  if (!shown || !content) {
    return (
      <Card role="status">
        <p className="text-body-sm text-muted-foreground">{t('screen.agreement.crew.empty')}</p>
      </Card>
    )
  }
  return (
    <div aria-busy={loading} className={cn('flex w-full max-w-reading-max flex-col gap-5', loading && 'opacity-60')}>
      {agreement.versions.length > 1 && (
        <div className="flex flex-col gap-1">
          <label htmlFor={`${ids}-version`} className="text-label">
            {t('screen.agreement.version')}
          </label>
          <select
            id={`${ids}-version`}
            value={pick?.versionNo ?? shown.versionNo}
            onChange={(event) => {
              const option = agreement.versions.find((version) => version.versionNo === Number(event.target.value))
              if (option) {
                onPick(option)
              }
            }}
            className={inputClass}
          >
            {[...agreement.versions].reverse().map((version) => (
              <option key={version.versionNo} value={version.versionNo}>
                {t('screen.agreement.review.confirmedOption', { n: version.versionNo, date: formatDayMonth(version.at) })}
              </option>
            ))}
          </select>
        </div>
      )}
      {shown.confirmed ? (
        <Card as="section" role="status" className="flex flex-col gap-1 text-body-sm">
          <p className="flex items-center gap-2 font-semibold">
            <CircleCheck aria-hidden="true" className="size-5 text-success" />
            {t('screen.agreement.review.confirmedTitle', { n: shown.versionNo })}
          </p>
          <p className="text-muted-foreground">
            {t('screen.agreement.review.confirmedAt', { time: formatDayMonthTime(shown.at) })}
          </p>
        </Card>
      ) : (
        <p role="status" className="flex gap-2 rounded-md border-l-4 border-info bg-info-soft p-4 text-body-sm">
          <Info aria-hidden="true" className="size-5 shrink-0 text-primary" />
          {t('screen.agreement.crew.shared', { n: shown.versionNo, time: formatDayMonthTime(shown.at) })}
        </p>
      )}
      <AgreementContentView
        content={content}
        reworkDays={agreement.reworkDays}
        review={{
          jobId: '',
          versionNo: shown.versionNo,
          comments: [],
          canComment: false,
          live: new Set(agreement.liveAccessIds),
        }}
      />
    </div>
  )
}
