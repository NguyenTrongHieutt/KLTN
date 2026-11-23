import { Skeleton } from '@/components/Skeleton'
import { Page } from '@/components/ui/page'
import { t } from '@/i18n/t'

/**
 * What the signed-in shell shows while the session or the shell itself loads: the top bar and a page shaped like
 * a heading over cards, so the screen keeps its frame instead of a bare "Đang tải…" line. Announced once,
 * politely.
 */
export function ShellSkeleton() {
  return (
    <div className="flex min-h-dvh flex-col" aria-busy="true">
      <p role="status" className="sr-only">
        {t('state.loading')}
      </p>
      <div className="border-b border-border bg-card">
        <div className="mx-auto flex min-h-header-height max-w-content-max items-center gap-6 px-gutter-mobile md:px-gutter-desktop">
          <span className="text-h3 font-bold text-primary">{t('app.name')}</span>
          <Skeleton className="hidden h-4 w-80 md:block" />
          <span className="grow" />
          <Skeleton className="size-10 rounded-full" />
        </div>
      </div>
      <Page>
        <Skeleton className="h-8 w-56" />
        <Skeleton className="h-28 w-full rounded-lg" />
        <Skeleton className="h-28 w-full rounded-lg" />
      </Page>
    </div>
  )
}
