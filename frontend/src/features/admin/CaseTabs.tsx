import { Link } from 'react-router'
import { t } from '@/i18n/t'
import { cn } from '@/lib/utils'

/** ADM-4's two kinds of case (UX-DR84): Disputes and, in Tier 3, the escalated Team Issues. */
export function CaseTabs({ current }: { current: 'disputes' | 'teamIssues' }) {
  const tabs = [
    { key: 'disputes', to: '/quan-tri/tranh-chap', label: t('screen.admin.teamIssues.tabDisputes') },
    { key: 'teamIssues', to: '/quan-tri/khieu-nai-noi-bo', label: t('screen.admin.teamIssues.tabTeamIssues') },
  ] as const
  return (
    <nav aria-label={t('screen.admin.disputes.title')} className="flex flex-wrap gap-2">
      {tabs.map((tab) => (
        <Link
          key={tab.key}
          to={tab.to}
          aria-current={tab.key === current ? 'page' : undefined}
          className={cn(
            'flex min-h-control-compact items-center rounded-full border px-3 text-label focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring',
            tab.key === current ? 'border-primary bg-primary-soft text-foreground' : 'border-border bg-card text-muted-foreground hover:bg-muted',
          )}
        >
          {tab.label}
        </Link>
      ))}
    </nav>
  )
}
