import type { ReactNode } from 'react'
import { Link, Navigate, useParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { buttonVariants } from '@/components/ui/button-variants'
import { useSession } from '@/features/identity/useSession'
import { useJob } from '@/features/job/jobApi'
import { JobHeader } from '@/features/job/JobHeader'
import { customerMeta, mentorMeta } from '@/features/job/jobMeta'
import { JobTabs } from '@/features/job/JobTabs'
import { MentorJobTabs } from '@/features/matching/MentorJobTabs'
import { useMentorJob } from '@/features/matching/mentorJobApi'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { hasBoard } from '@/features/work/workApi'
import { t, type MessageKey } from '@/i18n/t'
import { formatDate, formatDayMonth } from '@/lib/dates'
import { isNotFound } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { useHandover, useHandovers, type HandoverList } from './deliveryApi'
import { DeliveryReportView } from './DeliveryReportView'

/**
 * JOB-5 "Bàn giao" (FR-32, UX-DR53, UX-DR68): the Job's handovers and, when one is chosen, its Delivery
 * Report. The Mentor sees "Tạo bàn giao" while a Milestone waits for its handover; the Customer reads
 * each report with the date to check by; Crew members read only.
 */
export function HandoverPage() {
  const { jobId, handoverId } = useParams()
  const account = useSession().data?.account
  if (!jobId || (account !== undefined && account.role === 'ADMINISTRATOR')) {
    return <NotFoundScreen />
  }
  if (account?.role === 'CUSTOMER') {
    return <CustomerHandovers jobId={jobId} handoverId={handoverId} />
  }
  return account?.role === 'MENTOR' ? (
    <MentorHandovers jobId={jobId} handoverId={handoverId} />
  ) : (
    <Content jobId={jobId} handoverId={handoverId} header={null} />
  )
}

function CustomerHandovers({ jobId, handoverId }: { jobId: string; handoverId: string | undefined }) {
  const job = useJob(jobId)
  if (job.isError && isNotFound(job.error)) {
    return <NotFoundScreen />
  }
  if (job.data && !hasBoard(job.data.state)) {
    return <Navigate to={`/cong-viec/${job.data.id}`} replace />
  }
  return (
    <Content
      jobId={jobId}
      handoverId={handoverId}
      header={
        <RegionState query={job} skeleton={<Skeleton className="h-24 w-full" />}>
          {job.data && (
            <JobHeader title={job.data.title} state={job.data.state} meta={customerMeta(job.data)}>
              <JobTabs
                jobId={job.data.id}
                current="handover"
                chat={{ unread: false }}
                agreement={job.data.agreementShared}
                payment={job.data.agreementConfirmed}
                docs
                handover
              />
            </JobHeader>
          )}
        </RegionState>
      }
    />
  )
}

function MentorHandovers({ jobId, handoverId }: { jobId: string; handoverId: string | undefined }) {
  const job = useMentorJob(jobId)
  if (job.isError && isNotFound(job.error)) {
    return <NotFoundScreen />
  }
  if (job.data && !hasBoard(job.data.state)) {
    return <Navigate to={`/cong-viec/${job.data.id}`} replace />
  }
  return (
    <Content
      jobId={jobId}
      handoverId={handoverId}
      header={
        <RegionState query={job} skeleton={<Skeleton className="h-24 w-full" />}>
          {job.data && (
            <JobHeader title={job.data.title} state={job.data.state} meta={mentorMeta(job.data)} audience="mentor">
              <MentorJobTabs jobId={job.data.id} state={job.data.state} current="handover" payment={job.data.agreementConfirmed} />
            </JobHeader>
          )}
        </RegionState>
      }
    />
  )
}

function Content({ jobId, handoverId, header }: { jobId: string; handoverId: string | undefined; header: ReactNode }) {
  const list = useHandovers(jobId)
  const report = useHandover(handoverId)
  usePageTitle(list.data ? t('screen.handover.title', { job: list.data.title }) : t('screen.handover.tab'))
  if ((list.isError && isNotFound(list.error)) || (report.isError && isNotFound(report.error))) {
    return <NotFoundScreen />
  }
  return (
    <div className="mx-auto flex max-w-[1280px] flex-col gap-5 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      {header ?? (
        <div className="flex flex-col gap-1">
          <Link to={`/cong-viec/${jobId}`} className="w-fit text-label text-muted-foreground underline-offset-4 hover:underline">
            {t('screen.handover.back')}
          </Link>
          <PageHeading>{list.data?.title ?? t('screen.handover.tab')}</PageHeading>
          <p className="text-body-sm text-muted-foreground">{t('screen.handover.readOnly')}</p>
        </div>
      )}
      <RegionState query={list} skeleton={<Skeleton className="h-48 w-full" />}>
        {list.data && (
          <div className="grid gap-5 lg:grid-cols-[320px_minmax(0,1fr)]">
            <HandoverListPanel list={list.data} jobId={jobId} current={handoverId} />
            <div className="min-w-0">
              {handoverId ? (
                <RegionState query={report} skeleton={<Skeleton className="h-96 w-full" />}>
                  {report.data && <DeliveryReportView report={report.data} />}
                </RegionState>
              ) : (
                <p className="text-body-sm text-muted-foreground">
                  {list.data.handovers.length === 0 ? t('screen.handover.none') : t('screen.handover.pick')}
                </p>
              )}
            </div>
          </div>
        )}
      </RegionState>
    </div>
  )
}

function HandoverListPanel({ list, jobId, current }: { list: HandoverList; jobId: string; current: string | undefined }) {
  return (
    <nav aria-label={t('screen.handover.list')} className="flex flex-col gap-3">
      {list.waiting && (
        <div className="flex flex-col gap-2 rounded-lg border-l-4 border-primary bg-primary-soft p-4 text-body-sm">
          <p>{t('screen.handover.waiting', { n: list.waiting.number, title: list.waiting.title })}</p>
          <Link to={`/cong-viec/${jobId}/ban-giao/tao`} className={cn(buttonVariants({ size: 'compact' }), 'self-start')}>
            {t('screen.handover.create')}
          </Link>
        </div>
      )}
      <ul className="flex flex-col gap-2">
        {[...list.handovers].reverse().map((handover) => (
          <li key={handover.id}>
            <Link
              to={`/cong-viec/${jobId}/ban-giao/${handover.id}`}
              aria-current={handover.id === current ? 'page' : undefined}
              className={cn(
                'flex flex-col gap-0.5 rounded-lg border p-3 text-body-sm no-underline',
                handover.id === current ? 'border-primary bg-primary-soft' : 'border-border bg-card hover:bg-muted',
              )}
            >
              <span className="font-semibold break-words">
                {t('screen.handover.item', { n: handover.number, title: handover.milestoneTitle })}
              </span>
              <span className="text-caption text-muted-foreground">
                {t('screen.handover.itemMeta', { round: handover.round, date: formatDate(handover.createdAt) })}
              </span>
              <span className="text-caption">
                {list.audience === 'CUSTOMER'
                  ? t('screen.handover.status.customer', { date: formatDayMonth(handover.checkBy) })
                  : t(`screen.handover.status.${handover.status}` as MessageKey)}
              </span>
            </Link>
          </li>
        ))}
      </ul>
    </nav>
  )
}
