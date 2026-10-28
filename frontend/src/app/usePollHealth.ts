import { useQueryClient } from '@tanstack/react-query'
import { useSyncExternalStore } from 'react'
import { ApiError } from '@/lib/apiClient'

/**
 * Whether the polls reach the server (UX-DR26): offline when the browser says so, or when a polled
 * query (`meta.poll`) that a screen still observes failed its last fetch: a Job's to-dos left behind on leaving the
 * Job count for nothing. While offline, the quiet line "Mất kết nối · đang thử lại" shows under the top bar and
 * commands are disabled; nothing is queued. A poll answered 401
 * reached the server and found the session gone: that opens the session-expired dialog instead.
 */
export function usePollHealth(): { offline: boolean } {
  const queryClient = useQueryClient()
  const offline = useSyncExternalStore(
    (notify) => {
      const unsubscribe = queryClient.getQueryCache().subscribe(notify)
      window.addEventListener('online', notify)
      window.addEventListener('offline', notify)
      return () => {
        unsubscribe()
        window.removeEventListener('online', notify)
        window.removeEventListener('offline', notify)
      }
    },
    () =>
      !navigator.onLine ||
      queryClient
        .getQueryCache()
        .getAll()
        .some(
          (query) =>
            query.meta?.poll === true &&
            query.getObserversCount() > 0 &&
            query.state.status === 'error' &&
            !(query.state.error instanceof ApiError && query.state.error.status === 401),
        ),
    () => false,
  )
  return { offline }
}
