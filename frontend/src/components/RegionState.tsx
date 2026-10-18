import { useEffect, useState, type ReactNode } from 'react'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { problemMessage } from '@/lib/problemMessage'

/** After this long, a first load says "Vẫn đang tải…" and offers Retry (UX-DR26). */
export const STILL_LOADING_MS = 10_000

interface RegionQuery {
  isPending: boolean
  isError: boolean
  error: unknown
  refetch: () => unknown
}

/**
 * One region of a screen (UX-DR18, UX-DR26): its Skeleton on the first load, "Vẫn đang tải…" with
 * Retry after 10 s, its own inline error with Retry when the first load fails, and its content
 * otherwise. Polled refreshes after the first load stay silent; the rest of the screen keeps working.
 */
export function RegionState({
  query,
  skeleton,
  children,
}: {
  query: RegionQuery
  skeleton: ReactNode
  children: ReactNode
}) {
  const slow = useStillLoading(query.isPending)
  if (query.isPending) {
    return (
      <div aria-busy="true">
        {skeleton}
        {slow && (
          <div className="mt-3 flex flex-wrap items-center gap-3" role="status">
            <span className="text-body-sm text-muted-foreground">{t('state.stillLoading')}</span>
            <Button variant="secondary" size="compact" onClick={() => query.refetch()}>
              {t('state.retry')}
            </Button>
          </div>
        )}
      </div>
    )
  }
  if (query.isError) {
    return (
      <div className="flex flex-wrap items-center gap-3 rounded-md border-l-4 border-destructive bg-destructive-soft p-4" role="alert">
        <span className="text-body-sm">{problemMessage(query.error).text}</span>
        <Button variant="secondary" size="compact" onClick={() => query.refetch()}>
          {t('state.retry')}
        </Button>
      </div>
    )
  }
  return <>{children}</>
}

function useStillLoading(pending: boolean): boolean {
  const [slow, setSlow] = useState(false)
  useEffect(() => {
    if (!pending) {
      return undefined
    }
    const timer = window.setTimeout(() => setSlow(true), STILL_LOADING_MS)
    return () => window.clearTimeout(timer)
  }, [pending])
  return pending && slow
}
