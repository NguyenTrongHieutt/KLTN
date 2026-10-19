import { Link } from 'react-router'
import { t } from '@/i18n/t'
import { cn } from '@/lib/utils'

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
    <nav aria-label={t('screen.job.detail.tabs')} className="flex gap-4 overflow-x-auto border-b border-border">
      {tabs.map((tab) => (
        <Link
          key={tab.key}
          to={tab.to}
          aria-current={tab.key === current ? 'page' : undefined}
          className={cn(
            '-mb-px flex shrink-0 items-center gap-1.5 border-b-2 px-1 pb-2 text-label no-underline',
            tab.key === current ? 'border-primary font-semibold text-primary' : 'border-transparent text-muted-foreground hover:text-foreground',
          )}
        >
          {tab.label}
          {tab.dot && (
            <span className="size-2 rounded-full bg-destructive">
              <span className="sr-only">{t('screen.chat.unread')}</span>
            </span>
          )}
        </Link>
      ))}
    </nav>
  )
}

/**
 * The Customer's Job tabs (UX-DR17) as routes: "Tổng quan" always; "Đơn ứng tuyển ({n})" with
 * "Tìm người hướng dẫn" while the Job Post takes Applications (UX-DR66); "Trò chuyện" from Agreement
 * on, with the unread dot. Later tabs join with their stories.
 */
export function JobTabs({
  jobId,
  current,
  applications,
  chat,
}: {
  jobId: string
  current: 'overview' | 'applications' | 'find' | 'chat'
  /** The open Applications, when the tab is shown. */
  applications?: number
  /** The Job Chat, when the Job has one: whether the Mentor wrote since the Customer last read. */
  chat?: { unread: boolean }
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
  ]
  return <JobTabRow tabs={tabs} current={current} />
}
