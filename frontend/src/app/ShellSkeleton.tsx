import { Skeleton } from '@/components/Skeleton'
import { Page } from '@/components/ui/page'
import { t } from '@/i18n/t'
import { cn } from '@/lib/utils'
import { storedSidebarCollapsed } from './useSidebarCollapsed'

/**
 * What the signed-in shell shows while the session or the shell itself loads: the top bar, the sidebar and a page shaped
 * like a heading over cards, so the screen keeps its frame instead of a bare "Đang tải…" line. Announced once,
 * politely.
 */
export function ShellSkeleton() {
  // The sidebar this browser last chose, so the frame does not jump when the shell arrives.
  const collapsed = storedSidebarCollapsed()
  return (
    <div className="flex min-h-dvh flex-col" aria-busy="true">
      <p role="status" className="sr-only">
        {t('state.loading')}
      </p>
      <div className="border-b border-border bg-card">
        <div className="flex min-h-header-height items-center gap-3 px-gutter-mobile md:px-5">
          <span className="text-h2 font-bold tracking-tight text-primary lg:w-52">{t('app.name')}</span>
          <span className="flex grow justify-center">
            <Skeleton className="hidden h-10 w-full max-w-xl rounded-full md:block" />
          </span>
          <Skeleton className="size-10 rounded-full" />
        </div>
      </div>
      <div className="flex grow">
        <div
          className={cn(
            'hidden w-18 shrink-0 flex-col gap-2 border-r border-border bg-card px-1.5 py-3 md:flex',
            !collapsed && 'lg:w-60 lg:px-3',
          )}
        >
          {[0, 1, 2, 3].map((row) => (
            <Skeleton key={row} className="h-11 w-full rounded-lg" />
          ))}
        </div>
        <div className="min-w-0 grow">
          <Page>
            <Skeleton className="h-8 w-56" />
            <Skeleton className="h-28 w-full rounded-lg" />
            <Skeleton className="h-28 w-full rounded-lg" />
          </Page>
        </div>
      </div>
    </div>
  )
}
