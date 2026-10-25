import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { fetchSession, SESSION_QUERY_KEY, signOut, signUp } from './api'

/** The current session; every guard and the shell read it. */
export function useSession() {
  return useQuery({ queryKey: SESSION_QUERY_KEY, queryFn: fetchSession, staleTime: 30_000 })
}

/** PUB-3's acceptance of N-10; the signed-in session it answers replaces the welcome's. */
export function useSignUp() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ privacyNoticeVersion }: { privacyNoticeVersion: string }) => signUp(privacyNoticeVersion),
    onSuccess: (session) => queryClient.setQueryData(SESSION_QUERY_KEY, session),
  })
}

/** "Đăng xuất": ends the session and forgets everything the previous account loaded. */
export function useSignOut() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: signOut,
    onSuccess: () => {
      queryClient.clear()
    },
  })
}
