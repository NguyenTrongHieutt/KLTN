import type { ReactNode } from 'react'
import { Navigate, useParams } from 'react-router'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { ByJobRole } from '@/features/job/ByJobRole'
import { useJob, type Job } from '@/features/job/jobApi'
import { JobHeader } from '@/features/job/JobHeader'
import { customerMeta, mentorMeta } from '@/features/job/jobMeta'
import { JobTabs } from '@/features/job/JobTabs'
import { MentorJobTabs } from '@/features/matching/MentorJobTabs'
import { useMentorJob, type MentorJob } from '@/features/matching/mentorJobApi'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t } from '@/i18n/t'
import { isNotFound } from '@/lib/problemMessage'
import { ChatPanel } from './ChatPanel'
import { hasChat, useJobChat } from './chatApi'
import { hasBoard } from '@/features/work/workApi'

/**
 * JOB-4 "Trò chuyện" (`/cong-viec/{jobId}/tro-chuyen`, FR-46, UX-DR17): the Job Chat for its Customer
 * and its Mentor, from Agreement on and read-only once the Job ends; anyone else sees the not-found
 * screen, and a Job without a Chat goes back to its overview.
 */
export function JobChatPage() {
  const { jobId } = useParams()
  return (
    <ByJobRole
      jobId={jobId}
      customer={() => <CustomerChat jobId={jobId ?? ''} />}
      mentor={() => <MentorChat jobId={jobId ?? ''} />}
    />
  )
}

function CustomerChat({ jobId }: { jobId: string }) {
  const job = useJob(jobId)
  usePageTitle(job.data ? t('screen.chat.title', { job: job.data.title }) : t('screen.job.title'))
  if (job.isError && isNotFound(job.error)) {
    return <NotFoundScreen />
  }
  if (job.data && !hasChat(job.data.state)) {
    return <Navigate to={`/cong-viec/${job.data.id}`} replace />
  }
  return (
    <Frame>
      <RegionState query={job} skeleton={<Skeleton className="h-96 w-full" />}>
        {job.data && <CustomerHeader job={job.data} />}
      </RegionState>
      <JobChatPanel jobId={jobId} side="customer" />
    </Frame>
  )
}

function CustomerHeader({ job }: { job: Job }) {
  return (
    <JobHeader title={job.title} state={job.state} meta={customerMeta(job)}>
      <JobTabs
        jobId={job.id}
        current="chat"
        chat={{ unread: false }}
        agreement={job.agreementShared}
        payment={job.agreementConfirmed}
        docs={hasBoard(job.state)}
        handover={hasBoard(job.state)}
      />
    </JobHeader>
  )
}

function MentorChat({ jobId }: { jobId: string }) {
  const job = useMentorJob(jobId)
  usePageTitle(job.data ? t('screen.chat.title', { job: job.data.title }) : t('screen.job.title'))
  if (job.isError && isNotFound(job.error)) {
    return <NotFoundScreen />
  }
  if (job.data && !hasChat(job.data.state)) {
    return <Navigate to={`/cong-viec/${job.data.id}`} replace />
  }
  return (
    <Frame>
      <RegionState query={job} skeleton={<Skeleton className="h-96 w-full" />}>
        {job.data && <MentorHeader job={job.data} />}
      </RegionState>
      <JobChatPanel jobId={jobId} side="mentor" />
    </Frame>
  )
}

function MentorHeader({ job }: { job: MentorJob }) {
  return (
    <JobHeader title={job.title} state={job.state} meta={mentorMeta(job)}>
      <MentorJobTabs jobId={job.id} state={job.state} current="chat" payment={job.agreementConfirmed} />
    </JobHeader>
  )
}

function JobChatPanel({ jobId, side }: { jobId: string; side: 'customer' | 'mentor' }) {
  const chat = useJobChat(jobId)
  return (
    <ChatPanel
      target={{ kind: 'job', jobId }}
      heading={t(side === 'customer' ? 'screen.chat.withMentor' : 'screen.chat.withCustomer')}
      audience={t(side === 'customer' ? 'screen.chat.onlyMentor' : 'screen.chat.onlyCustomer')}
      placeholder={t(side === 'customer' ? 'screen.chat.placeholderMentor' : 'screen.chat.placeholderCustomer')}
      readOnlyNote={chat.data?.readOnly ? t('screen.chat.readOnly') : undefined}
    />
  )
}

function Frame({ children }: { children: ReactNode }) {
  return (
    <div className="mx-auto flex max-w-[1120px] flex-col gap-5 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      {children}
    </div>
  )
}
