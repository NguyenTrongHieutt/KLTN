import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { fetchSession, SESSION_QUERY_KEY, signOut, signUp, type SignUpRole } from './api'

/** The current session; every guard and the shell read it. */
export function useSession() {
  return useQuery({ queryKey: SESSION_QUERY_KEY, queryFn: fetchSession, staleTime: 30_000 })
}

export function useSignUp() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ role, privacyNoticeVersion }: { role: SignUpRole; privacyNoticeVersion: string }) =>
      signUp(role, privacyNoticeVersion),
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
