import { hasChat, useCrewChat, useJobChat } from '@/features/chat/chatApi'
import { JobTabRow } from '@/features/job/JobTabs'
import { hasBoard } from '@/features/work/workApi'
import { t } from '@/i18n/t'

/**
 * The Mentor's Job tabs (UX-DR17): "Tổng quan"; from Active on, "Bảng công việc" (JOB-2); from
 * Agreement on, "Trò chuyện" and "Trao đổi nhóm" (JOB-8), each with its unread dot, and "Thỏa thuận";
 * "Thanh toán" once the Agreement is confirmed (JOB-7).
 */
export function MentorJobTabs({
  jobId,
  state,
  current,
  payment = false,
}: {
  jobId: string
  state: string
  current: 'overview' | 'board' | 'chat' | 'crewChat' | 'agreement' | 'payment'
  /** Whether the Agreement is confirmed (FR-17). */
  payment?: boolean
}) {
  const chat = useJobChat(hasChat(state) ? jobId : undefined)
  const crewChat = useCrewChat(hasChat(state) ? jobId : undefined)
  return (
    <JobTabRow
      current={current}
      tabs={[
        { key: 'overview', to: `/cong-viec/${jobId}`, label: t('screen.job.detail.overview') },
        ...(hasBoard(state) ? [{ key: 'board', to: `/cong-viec/${jobId}/bang`, label: t('screen.board.tab') }] : []),
        ...(hasChat(state)
          ? [
              { key: 'chat', to: `/cong-viec/${jobId}/tro-chuyen`, label: t('screen.chat.tab'), dot: chat.data?.unread },
              {
                key: 'crewChat',
                to: `/cong-viec/${jobId}/trao-doi-nhom`,
                label: t('screen.crewChat.tab'),
                dot: crewChat.data?.unread,
              },
              { key: 'agreement', to: `/cong-viec/${jobId}/thoa-thuan`, label: t('screen.agreement.tab') },
            ]
          : []),
        ...(payment ? [{ key: 'payment', to: `/cong-viec/${jobId}/thanh-toan`, label: t('screen.payment.tab') }] : []),
      ]}
    />
  )
}
