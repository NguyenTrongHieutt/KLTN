import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { NOTIFICATION_QUERY_KEY } from '@/features/notification/api'
import { apiClient, formBody, unwrap } from '@/lib/apiClient'
import { SESSION_QUERY_KEY, type Session } from './api'

export type Profile = components['schemas']['ProfileView']
export type CustomerProfile = components['schemas']['CustomerProfileRequest']
export type StudentProfile = components['schemas']['StudentProfileRequest']
export type MentorApplication = components['schemas']['MentorApplicationRequest']
export type MentorProfile = components['schemas']['MentorProfileView']
export type Theme = components['schemas']['ThemeRequest']['theme']

export const PROFILE_QUERY_KEY = ['identity', 'profile'] as const

/** The caller's own profile (CUS-11, STU-5). */
export function useProfile() {
  return useQuery({ queryKey: PROFILE_QUERY_KEY, queryFn: () => unwrap(apiClient.GET('/api/v1/profile')) })
}

export function useSaveCustomerProfile() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (profile: CustomerProfile) => unwrap(apiClient.PUT('/api/v1/profile/customer', { body: profile })),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: PROFILE_QUERY_KEY }),
  })
}

export function useSaveStudentProfile() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (profile: StudentProfile) => unwrap(apiClient.PUT('/api/v1/profile/student', { body: profile })),
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

/**
 * MEN-5's save (FR-74): the first one submits the application, which closes the
 * `identity.complete_mentor_application` to-do, so the to-dos refresh too.
 */
export function useSaveMentorProfile() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (application: MentorApplication) =>
      unwrap(apiClient.PUT('/api/v1/profile/mentor', { body: application })),
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: PROFILE_QUERY_KEY }),
        queryClient.invalidateQueries({ queryKey: NOTIFICATION_QUERY_KEY }),
      ]),
  })
}

/** The Mentor's document, a CV or a certificate, in place of the earlier one (AD-21). */
export function useUploadMentorDocument() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (file: File) => {
      const form = new FormData()
      form.append('file', file)
      return unwrap(apiClient.POST('/api/v1/profile/mentor/document', { body: formBody(form) }))
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: PROFILE_QUERY_KEY }),
  })
}

export function useDeleteMentorDocument() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => unwrap(apiClient.DELETE('/api/v1/profile/mentor/document')),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: PROFILE_QUERY_KEY }),
  })
}
