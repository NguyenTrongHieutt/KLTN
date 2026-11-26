import { TabNav } from '@/components/ui/tab-nav'
import { useCrewChat } from '@/features/chat/chatApi'
import { useJobSplits } from '@/features/split/splitApi'
import { t } from '@/i18n/t'
import { useJobRole } from './jobRoleApi'
import { hasStarted } from './jobState'

/** One tab of a Job's tab row, as a route; an unread dot when something new waits there (UX-DR17). */
export interface JobTab {
  key: string
  to: string
  label: string
  dot?: boolean
}

/** The tab row a Job's screens share; the current tab carries `aria-current="page"`. */
export function JobTabRow({ tabs, current }: { tabs: JobTab[]; current: string }) {
  return (
    <TabNav
      label={t('screen.job.detail.tabs')}
      current={current}
      items={tabs.map((tab) => ({ ...tab, dot: tab.dot ? t('screen.chat.unread') : undefined }))}
    />
  )
}

/** The states the Crew is recruited in: from Agreement until Closing (AD-41). */
const RECRUITS = ['AGREEMENT', 'STARTING', 'ACTIVE']

/**
 * The Job tabs of the Customer of a Self-mentored Job (FR-85, AD-47, UX-DR17), one row on every screen, whichever
 * side that screen is: "Tổng quan"; "Bàn giao" (JOB-5) from Active on; "Trao đổi nhóm" (JOB-8) with its unread dot,
 * the Job's only channel, as there is no "Trò chuyện"; "Thỏa thuận", which the Customer drafts; "Tuyển thành viên"
 * (JOB-9) until Closing; "Thanh toán" (JOB-7) once the Agreement is confirmed; "Phân chia tiền" (JOB-10) from the
 * first split on. {@link JobTabs} and the Mentor's row give way to it for that role.
 */
export function SelfMentoredJobTabs({
  jobId,
  state,
  current,
  payment = false,
}: {
  jobId: string
  /** The Job's state; while it is unknown the row holds the tabs every state has. */
  state?: string
  /** The screen's own name for its tab; one the row does not hold marks none. */
  current: string
  /** Whether the Agreement is confirmed (FR-17). */
  payment?: boolean
}) {
  const crewChat = useCrewChat(jobId)
  const splits = useJobSplits(jobId)
  return (
    <JobTabRow
      current={current}
      tabs={[
        { key: 'overview', to: `/cong-viec/${jobId}`, label: t('screen.job.detail.overview') },
        ...(state !== undefined && hasStarted(state)
          ? [{ key: 'handover', to: `/cong-viec/${jobId}/ban-giao`, label: t('screen.handover.tab') }]
          : []),
        {
          key: 'crewChat',
          to: `/cong-viec/${jobId}/trao-doi-nhom`,
          label: t('screen.crewChat.tab'),
          dot: crewChat.data?.unread,
        },
        { key: 'agreement', to: `/cong-viec/${jobId}/thoa-thuan`, label: t('screen.agreement.tab') },
        ...(state !== undefined && RECRUITS.includes(state)
          ? [{ key: 'recruitment', to: `/cong-viec/${jobId}/tuyen-thanh-vien`, label: t('screen.recruitment.tab') }]
          : []),
        ...(payment ? [{ key: 'payment', to: `/cong-viec/${jobId}/thanh-toan`, label: t('screen.payment.tab') }] : []),
        ...(splits.data?.length
          ? [{ key: 'split', to: `/cong-viec/${jobId}/phan-chia`, label: t('screen.split.tab') }]
          : []),
      ]}
    />
  )
}

/**
 * The Customer's Job tabs (UX-DR17) as routes: "Tổng quan" always; "Đơn ứng tuyển ({n})" with
 * "Tìm người hướng dẫn" while the Job Post takes Applications (UX-DR66); "Trò chuyện" from Agreement
 * on, with the unread dot; "Thỏa thuận" once the Mentor shared a draft; "Thanh toán" once the
 * Agreement is confirmed (JOB-7); "Bàn giao" (JOB-5) from Active on. Later tabs join with
 * their stories.
 * The Customer of a Self-mentored Job gets {@link SelfMentoredJobTabs} in its place.
 */
export function JobTabs(props: Parameters<typeof CustomerJobTabs>[0] & { state?: string }) {
  const role = useJobRole(props.jobId).data?.role
  if (role === 'CUSTOMER_MENTOR') {
    return <SelfMentoredJobTabs jobId={props.jobId} state={props.state} current={props.current} payment={props.payment} />
  }
  return <CustomerJobTabs {...props} />
}

function CustomerJobTabs({
  jobId,
  current,
  applications,
  chat,
  agreement = false,
  payment = false,
  handover = false,
}: {
  jobId: string
  current: 'overview' | 'applications' | 'find' | 'chat' | 'agreement' | 'payment' | 'handover'
  /** The open Applications, when the tab is shown. */
  applications?: number
  /** The Job Chat, when the Job has one: whether the Mentor wrote since the Customer last read. */
  chat?: { unread: boolean }
  /** Whether the Mentor shared an Agreement draft (FR-16). */
  agreement?: boolean
  /** Whether the Agreement is confirmed, so its Milestones can be funded (FR-41). */
  payment?: boolean
  /** Whether the Job is Active or later, so its Milestones are handed over on JOB-5 (FR-32). */
  handover?: boolean
}) {
  const tabs: JobTab[] = [
    { key: 'overview', to: `/cong-viec/${jobId}`, label: t('screen.job.detail.overview') },
    ...(applications === undefined
      ? []
      : [
          { key: 'applications', to: `/cong-viec/${jobId}/ung-tuyen`, label: t('screen.matching.tab', { n: applications }) },
          { key: 'find', to: `/cong-viec/${jobId}/tim-nguoi`, label: t('screen.matching.find.tab') },
        ]),
    ...(chat === undefined
      ? []
      : [{ key: 'chat', to: `/cong-viec/${jobId}/tro-chuyen`, label: t('screen.chat.tab'), dot: chat.unread }]),
    ...(agreement ? [{ key: 'agreement', to: `/cong-viec/${jobId}/thoa-thuan`, label: t('screen.agreement.tab') }] : []),
    ...(payment ? [{ key: 'payment', to: `/cong-viec/${jobId}/thanh-toan`, label: t('screen.payment.tab') }] : []),
    ...(handover ? [{ key: 'handover', to: `/cong-viec/${jobId}/ban-giao`, label: t('screen.handover.tab') }] : []),
  ]
  return <JobTabRow tabs={tabs} current={current} />
}
