import { MutationCache, QueryClient } from '@tanstack/react-query'
import { sessionExpiry } from '@/features/identity/sessionExpiry'

/** The app's query client: a command that finds the session gone opens "Phiên đăng nhập đã hết". */
export function createQueryClient(): QueryClient {
  return new QueryClient({ mutationCache: new MutationCache({ onError: sessionExpiry.report }) })
}
