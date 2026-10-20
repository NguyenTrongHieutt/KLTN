import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'
import { OVERVIEW_QUERY_KEY } from './adminApi'

export type AdminUsers = components['schemas']['UsersView']
export type AdminUserRow = components['schemas']['UserRowView']
export type AdminUser = components['schemas']['UserDetailView']
export type UserRole = 'CUSTOMER' | 'MENTOR' | 'STUDENT'
export type UserFilter = 'LOCKED' | 'VERIFIED' | 'PENDING' | 'APPROVED' | 'REJECTED' | 'REVOKED' | 'LOW_FEEDBACK'
export type FeedbackCategory = 'RESPECT' | 'GUIDANCE' | 'RULES'

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

/** One account with its history and, for a Mentor, the private feedback (FR-78). */
export function useAdminUser(accountId: string) {
  return useQuery({
    queryKey: [...USERS_QUERY_KEY, 'detail', accountId],
    queryFn: () => unwrap(apiClient.GET('/api/v1/admin/users/{accountId}', { params: { path: { accountId } } })),
    retry: false,
  })
}

export type UserCommand =
  | { action: 'lock' | 'unlock' | 'revoke-verification'; reason: string }
  | {
      action: 'revoke-approval'
      body: { ground: 'OTHER' | 'PRIVATE_FEEDBACK'; reason?: string; category?: FeedbackCategory; stepUpCode: string }
    }

/** Lock, unlock, or a revocation; the account, the list, and ADM-1 refresh. */
export function useUserCommand(accountId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (command: UserCommand) => {
      const path = { params: { path: { accountId } } }
      switch (command.action) {
        case 'lock':
          return unwrap(apiClient.POST('/api/v1/admin/users/{accountId}/lock', { ...path, body: { reason: command.reason } }))
        case 'unlock':
          return unwrap(apiClient.POST('/api/v1/admin/users/{accountId}/unlock', { ...path, body: { reason: command.reason } }))
        case 'revoke-verification':
          return unwrap(
            apiClient.POST('/api/v1/admin/users/{accountId}/revoke-verification', { ...path, body: { reason: command.reason } }),
          )
        case 'revoke-approval':
          return unwrap(apiClient.POST('/api/v1/admin/users/{accountId}/revoke-approval', { ...path, body: command.body }))
      }
    },
    onSuccess: (detail) => queryClient.setQueryData([...USERS_QUERY_KEY, 'detail', accountId], detail),
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: [...USERS_QUERY_KEY, 'list'] }),
        queryClient.invalidateQueries({ queryKey: OVERVIEW_QUERY_KEY }),
      ]),
  })
}
