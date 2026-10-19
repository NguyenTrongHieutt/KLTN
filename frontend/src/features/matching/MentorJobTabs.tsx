import { hasChat, useJobChat } from '@/features/chat/chatApi'
import { JobTabRow } from '@/features/job/JobTabs'
import { t } from '@/i18n/t'

/** The Mentor's Job tabs (UX-DR17): "Tổng quan", and "Trò chuyện" from Agreement on with its unread dot. */
export function MentorJobTabs({ jobId, state, current }: { jobId: string; state: string; current: 'overview' | 'chat' }) {
  const chat = useJobChat(hasChat(state) ? jobId : undefined)
  return (
    <JobTabRow
      current={current}
      tabs={[
        { key: 'overview', to: `/cong-viec/${jobId}`, label: t('screen.job.detail.overview') },
        ...(hasChat(state)
          ? [{ key: 'chat', to: `/cong-viec/${jobId}/tro-chuyen`, label: t('screen.chat.tab'), dot: chat.data?.unread }]
          : []),
      ]}
    />
  )
}
