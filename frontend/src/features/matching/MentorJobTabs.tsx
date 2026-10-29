import { hasChat, useCrewChat, useJobChat } from '@/features/chat/chatApi'
import { JobTabRow } from '@/features/job/JobTabs'
import { hasBoard } from '@/features/work/workApi'
import { t } from '@/i18n/t'

/** The states the Mentor recruits the Crew in: from J4 until J8 (AD-41). */
const RECRUITS = ['AGREEMENT', 'STARTING', 'ACTIVE']

/**
 * The Mentor's Job tabs (UX-DR17): "Tổng quan"; from Active on, "Bảng công việc" (JOB-2), "Tài liệu"
 * (JOB-3), and "Bàn giao" (JOB-5); from
 * Agreement on, "Trò chuyện" and "Trao đổi nhóm" (JOB-8), each with its unread dot, and "Thỏa thuận";
 * "Tuyển thành viên" (JOB-9) from Agreement until Closing (Story 21.4); "Thanh toán" once the Agreement is confirmed
 * (JOB-7).
 */
export function MentorJobTabs({
  jobId,
  state,
  current,
  payment = false,
}: {
  jobId: string
  state: string
  current: 'overview' | 'board' | 'docs' | 'handover' | 'chat' | 'crewChat' | 'agreement' | 'recruitment' | 'payment'
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
        ...(hasBoard(state)
          ? [
              { key: 'board', to: `/cong-viec/${jobId}/bang`, label: t('screen.board.tab') },
              { key: 'docs', to: `/cong-viec/${jobId}/tai-lieu`, label: t('screen.docs.tab') },
              { key: 'handover', to: `/cong-viec/${jobId}/ban-giao`, label: t('screen.handover.tab') },
            ]
          : []),
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
        ...(RECRUITS.includes(state)
          ? [{ key: 'recruitment', to: `/cong-viec/${jobId}/tuyen-thanh-vien`, label: t('screen.recruitment.tab') }]
          : []),
        ...(payment ? [{ key: 'payment', to: `/cong-viec/${jobId}/thanh-toan`, label: t('screen.payment.tab') }] : []),
      ]}
    />
  )
}
