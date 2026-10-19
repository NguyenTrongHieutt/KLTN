import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { POLLED, POLLING, pollEvery } from '@/config/polling'
import { SESSION_QUERY_KEY } from '@/features/identity/api'
import { fetchNotifications, NOTIFICATION_QUERY_KEY } from '@/features/notification/api'
import { apiClient, unwrap } from '@/lib/apiClient'

export type AdminSignIn = components['schemas']['AdminSignInRequest']

export type Overview = components['schemas']['OverviewView']

export type AdministratorRow = components['schemas']['AdministratorListItem']

export type CreateAdministrator = components['schemas']['CreateAdministratorRequest']

export type Enrolment = components['schemas']['EnrolmentView']

export const ADMINISTRATORS_QUERY_KEY = ['admin', 'administrators'] as const

export const OVERVIEW_QUERY_KEY = ['admin', 'overview'] as const

/** PUB-6: signs an Administrator in; the session then says who, with its limits (SG-10). */
export function useAdminSignIn() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: AdminSignIn) => unwrap(apiClient.POST('/api/v1/admin/sign-in', { body })),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: SESSION_QUERY_KEY }),
  })
}

/** ADM-1's queue counts, every 60 s (UX-DR69). */
export function useAdminOverview() {
  return useQuery({
    queryKey: OVERVIEW_QUERY_KEY,
    queryFn: () => unwrap(apiClient.GET('/api/v1/admin/overview')),
    refetchInterval: pollEvery(POLLING.adminOverview),
    retry: false,
    meta: POLLED,
  })
}

/** ADM-1's alert list: the Administrator's 10 latest notifications, every 60 s. */
export function useAdminAlerts() {
  return useQuery({
    queryKey: [...NOTIFICATION_QUERY_KEY, 'admin-alerts'],
    queryFn: () => fetchNotifications(0, 10),
    refetchInterval: pollEvery(POLLING.adminOverview),
    retry: false,
    meta: POLLED,
  })
}

/**
 * "Tiếp tục phiên", and the ping after real input: a command, so it extends the Administrator
 * session's idle limit; the answer carries the new limits.
 */
export function useSessionActivity() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => unwrap(apiClient.POST('/api/v1/session/activity')),
    onSuccess: (session) => queryClient.setQueryData(SESSION_QUERY_KEY, session),
  })
}

/** ADM-11's list (FR-2). */
export function useAdministrators() {
  return useQuery({
    queryKey: ADMINISTRATORS_QUERY_KEY,
    queryFn: () => unwrap(apiClient.GET('/api/v1/admin/administrators')),
  })
}

/** Creates an Administrator after the step-up (SG-10); the list and ADM-1 refresh. */
export function useCreateAdministrator() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateAdministrator) => unwrap(apiClient.POST('/api/v1/admin/administrators', { body })),
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: ADMINISTRATORS_QUERY_KEY }),
        queryClient.invalidateQueries({ queryKey: OVERVIEW_QUERY_KEY }),
      ]),
  })
}

/** The enrolment link: the account and its authenticator secret (FR-2). */
export function useEnrolment(token: string) {
  return useQuery({
    queryKey: ['admin', 'enrolment', token],
    queryFn: () => unwrap(apiClient.GET('/api/v1/admin/enrolment/{token}', { params: { path: { token } } })),
    retry: false,
    staleTime: Infinity,
  })
}

/** Sets the password and enrols the app; the new Administrator is then signed in. */
export function useCompleteEnrolment(token: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: { password: string; code: string }) =>
      unwrap(apiClient.POST('/api/v1/admin/enrolment/{token}', { params: { path: { token } }, body })),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: SESSION_QUERY_KEY }),
  })
}
