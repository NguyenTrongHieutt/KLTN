import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'
import { SESSION_QUERY_KEY, type Session } from './api'

export type Profile = components['schemas']['ProfileView']
export type ProfileRequest = components['schemas']['ProfileRequest']
export type Theme = components['schemas']['ThemeRequest']['theme']

export const PROFILE_QUERY_KEY = ['identity', 'profile'] as const

/** ACC-1: the caller's one profile, with what is verified (Story 19.3). */
export function useProfile() {
  return useQuery({ queryKey: PROFILE_QUERY_KEY, queryFn: () => unwrap(apiClient.GET('/api/v1/profile')) })
}

/** Saves ACC-1's "Bạn tự nhập" zone; nothing verified is ever sent. */
export function useSaveProfile() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (profile: ProfileRequest) => unwrap(apiClient.PUT('/api/v1/profile', { body: profile })),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: PROFILE_QUERY_KEY }),
  })
}

/** Saves the Theme toggle's choice to the account; the session keeps it for the next visit (UX-DR5). */
export function useSaveTheme() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (theme: Theme) => unwrap(apiClient.PUT('/api/v1/account/theme', { body: { theme } })),
    onMutate: (theme) => {
      queryClient.setQueryData<Session>(SESSION_QUERY_KEY, (session) =>
        session?.account ? { ...session, account: { ...session.account, theme } } : session,
      )
    },
  })
}
