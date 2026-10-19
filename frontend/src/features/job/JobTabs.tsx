import { Link } from 'react-router'
import { t } from '@/i18n/t'
import { cn } from '@/lib/utils'

/**
 * The Customer's Job tabs (UX-DR17) as routes: "Tổng quan" always, and "Đơn ứng tuyển ({n})" while
 * the Job Post takes Applications. Later tabs join with their stories.
 */
export function JobTabs({
  jobId,
  current,
  applications,
}: {
  jobId: string
  current: 'overview' | 'applications'
  /** The open Applications, when the tab is shown. */
  applications?: number
}) {
  const tabs = [
    { key: 'overview', to: `/cong-viec/${jobId}`, label: t('screen.job.detail.overview') },
    ...(applications === undefined
      ? []
      : [{ key: 'applications', to: `/cong-viec/${jobId}/ung-tuyen`, label: t('screen.matching.tab', { n: applications }) }]),
  ]
  return (
    <nav aria-label={t('screen.job.detail.tabs')} className="flex gap-4 border-b border-border">
      {tabs.map((tab) => (
        <Link
          key={tab.key}
          to={tab.to}
          aria-current={tab.key === current ? 'page' : undefined}
          className={cn(
            '-mb-px border-b-2 px-1 pb-2 text-label no-underline',
            tab.key === current ? 'border-primary font-semibold text-primary' : 'border-transparent text-muted-foreground hover:text-foreground',
          )}
        >
          {tab.label}
        </Link>
      ))}
    </nav>
  )
}
