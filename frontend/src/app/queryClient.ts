import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query'
import { NOTIFICATION_QUERY_KEY } from '@/features/notification/api'
import { sessionExpiry } from '@/features/identity/sessionExpiry'

/**
 * The app's query client. A command, or a poll, that finds the session gone opens "Phiên đăng nhập
 * đã hết" over the screen, which keeps its input; any successful command refreshes the strip, the
 * to-dos, and the unread count (UX-DR14, UX-DR25, UX-DR26).
 */
export function createQueryClient(): QueryClient {
  const client: QueryClient = new QueryClient({
    queryCache: new QueryCache({
      onError: (error, query) => {
        if (query.meta?.poll === true) {
          sessionExpiry.report(error)
        }
      },
    }),
    mutationCache: new MutationCache({
      onError: sessionExpiry.report,
      onSuccess: () => client.invalidateQueries({ queryKey: NOTIFICATION_QUERY_KEY }),
    }),
  })
  return client
}
