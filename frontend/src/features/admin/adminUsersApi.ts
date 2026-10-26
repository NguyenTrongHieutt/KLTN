import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'
import { OVERVIEW_QUERY_KEY } from './adminApi'

export type AdminUsers = components['schemas']['UsersView']
export type AdminUserRow = components['schemas']['UserRowView']
export type AdminUser = components['schemas']['UserDetailView']
export type PersonLink = components['schemas']['PersonLinkView']
export type UserRole = 'CUSTOMER' | 'MENTOR' | 'STUDENT'
export type UserFilter = 'LOCKED' | 'VERIFIED' | 'LOW_FEEDBACK'

export interface UserQuery {
  q?: string
  role?: UserRole
  status?: UserFilter
  page: number
}

const USERS_QUERY_KEY = ['admin', 'users'] as const

/** ADM-2's search (FR-59): 20 accounts a page. */
export function useAdminUsers(query: UserQuery) {
  return useQuery({
    queryKey: [...USERS_QUERY_KEY, 'list', query],
    queryFn: () =>
      unwrap(
        apiClient.GET('/api/v1/admin/users', {
          params: { query: { q: query.q || undefined, role: query.role, status: query.status, page: query.page } },
        }),
      ),
    placeholderData: keepPreviousData,
    retry: false,
  })
}

/**
 * One account with its history, the accounts that may be the same person (AD-19), and, for a Mentor, the
 * private feedback (FR-78).
 */
export function useAdminUser(accountId: string) {
  return useQuery({
    queryKey: [...USERS_QUERY_KEY, 'detail', accountId],
    queryFn: () => unwrap(apiClient.GET('/api/v1/admin/users/{accountId}', { params: { path: { accountId } } })),
    retry: false,
  })
}

/** Lock or unlock with a reason; nobody is approved or revoked here (Story 19.7). */
export interface UserCommand {
  action: 'lock' | 'unlock'
  reason: string
}

/** "Xác nhận xóa tài khoản" behind the step-up (NFR-11); the list and ADM-1 refresh. */
export function useEraseUser(accountId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (stepUpCode: string) =>
      unwrap(apiClient.POST('/api/v1/admin/users/{accountId}/erase', { params: { path: { accountId } }, body: { stepUpCode } })),
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: USERS_QUERY_KEY }),
        queryClient.invalidateQueries({ queryKey: OVERVIEW_QUERY_KEY }),
      ]),
  })
}

/** Lock or unlock; the account, the list, and ADM-1 refresh. */
export function useUserCommand(accountId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (command: UserCommand) => {
      const path = { params: { path: { accountId } } }
      return command.action === 'lock'
        ? unwrap(apiClient.POST('/api/v1/admin/users/{accountId}/lock', { ...path, body: { reason: command.reason } }))
        : unwrap(apiClient.POST('/api/v1/admin/users/{accountId}/unlock', { ...path, body: { reason: command.reason } }))
    },
    onSuccess: (detail) => queryClient.setQueryData([...USERS_QUERY_KEY, 'detail', accountId], detail),
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: [...USERS_QUERY_KEY, 'list'] }),
        queryClient.invalidateQueries({ queryKey: OVERVIEW_QUERY_KEY }),
      ]),
  })
}
