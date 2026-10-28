import type { ReactNode } from 'react'
import { Link, Navigate, useParams } from 'react-router'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { ByJobRole, JobRoleLine } from '@/features/job/ByJobRole'
import { JobHeader } from '@/features/job/JobHeader'
import { mentorMeta } from '@/features/job/jobMeta'
import { MentorJobTabs } from '@/features/matching/MentorJobTabs'
import { useMentorJob } from '@/features/matching/mentorJobApi'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { t } from '@/i18n/t'
import { isNotFound } from '@/lib/problemMessage'
import { ChatPanel } from './ChatPanel'
import { hasChat, useCrewChat, type CrewChat } from './chatApi'

/**
 * JOB-8 "Trao đổi nhóm" (`/cong-viec/{jobId}/trao-doi-nhom`, FR-77, UX-DR17, UX-DR80): the Crew Chat
 * between the Job's Mentor and its Crew, never shown to the Customer. The Mentor reaches it from the
 * Job's tabs, a Crew member from "Việc tôi tham gia"; a former member, one whose lock counted as leaving
 * included, reads only the messages of their time on the Crew. Anyone else sees the not-found screen.
 */
export function CrewChatPage() {
  const { jobId } = useParams()
  return (
    <ByJobRole
      jobId={jobId}
      mentor={() => <MentorCrewChat jobId={jobId ?? ''} />}
      crew={() => <StudentCrewChat jobId={jobId ?? ''} />}
    />
  )
}

function MentorCrewChat({ jobId }: { jobId: string }) {
  const job = useMentorJob(jobId)
  const chat = useCrewChat(job.data && hasChat(job.data.state) ? jobId : undefined)
  usePageTitle(job.data ? t('screen.crewChat.title', { job: job.data.title }) : t('screen.job.title'))
  if (job.isError && isNotFound(job.error)) {
    return <NotFoundScreen />
  }
  if (job.data && !hasChat(job.data.state)) {
    return <Navigate to={`/cong-viec/${job.data.id}`} replace />
  }
  return (
    <Frame>
      <RegionState query={job} skeleton={<Skeleton className="h-24 w-full" />}>
        {job.data && (
          <JobHeader title={job.data.title} state={job.data.state} meta={mentorMeta(job.data)}>
            <MentorJobTabs jobId={job.data.id} state={job.data.state} current="crewChat" payment={job.data.agreementConfirmed} />
          </JobHeader>
        )}
      </RegionState>
      {job.data && (
        <RegionState query={chat} skeleton={<Skeleton className="h-96 w-full" />}>
          {chat.data && <CrewThread jobId={jobId} chat={chat.data} />}
        </RegionState>
      )}
    </Frame>
  )
}

function StudentCrewChat({ jobId }: { jobId: string }) {
  const chat = useCrewChat(jobId)
  usePageTitle(chat.data ? t('screen.crewChat.title', { job: chat.data.title }) : t('screen.crewChat.tab'))
  if (chat.isError && isNotFound(chat.error)) {
    return <NotFoundScreen />
  }
  return (
    <Frame>
      <div className="flex flex-col gap-1">
        <Link to="/cong-viec" className="w-fit text-label text-muted-foreground underline-offset-4 hover:underline">
          {t('screen.crewChat.back')}
        </Link>
        <PageHeading>{chat.data?.title ?? t('screen.crewChat.tab')}</PageHeading>
        <p className="text-body-sm text-muted-foreground">{t('screen.crewChat.tab')}</p>
        <JobRoleLine jobId={jobId} />
      </div>
      <RegionState query={chat} skeleton={<Skeleton className="h-96 w-full" />}>
        {chat.data && <CrewThread jobId={jobId} chat={chat.data} />}
      </RegionState>
    </Frame>
  )
}

function CrewThread({ jobId, chat }: { jobId: string; chat: CrewChat }) {
  let note: string | undefined
  if (chat.closed) {
    note = t('screen.chat.readOnly')
  } else if (chat.readOnly) {
    note = t('screen.crewChat.formerNote')
  }
  return (
    <ChatPanel
      target={{ kind: 'crew', jobId, chatId: chat.chatId }}
      heading={t('screen.crewChat.heading')}
      audience={t('screen.crewChat.audience')}
      placeholder={t('screen.crewChat.placeholder')}
      empty={t('screen.crewChat.empty')}
      readOnlyNote={note}
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
