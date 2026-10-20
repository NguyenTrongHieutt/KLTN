import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { NOTIFICATION_QUERY_KEY } from '@/features/notification/api'
import { apiClient, unwrap } from '@/lib/apiClient'

export type ChangeRequests = components['schemas']['ChangeRequestsView']
export type ChangeRequest = components['schemas']['ChangeRequestView']
export type ConfirmedVersions = components['schemas']['ConfirmedVersionsView']

/** JOB-6's due-date Change Requests (FR-36). */
export function useChangeRequests(jobId: string) {
  return useQuery({
    queryKey: ['change-requests', jobId],
    queryFn: () =>
      unwrap(apiClient.GET('/api/v1/boards/{boardId}/change-requests', { params: { path: { boardId: jobId } } })),
    retry: false,
  })
}

/** Every confirmed Agreement version (AD-29). */
export function useConfirmedVersions(jobId: string) {
  return useQuery({
    queryKey: ['agreement-versions', jobId],
    queryFn: () =>
      unwrap(apiClient.GET('/api/v1/boards/{boardId}/agreement-versions', { params: { path: { boardId: jobId } } })),
    retry: false,
  })
}

type Command =
  | {
      action: 'propose'
      body: { description?: string; impact?: string; items: { milestoneId: string; newDueOn: string }[]; version: number }
    }
  | { action: 'impact'; requestId: string; impact: string }
  | { action: 'approve' | 'confirm'; requestId: string; version: number }
  | { action: 'withdraw'; requestId: string }

/** Every Change Request command; each answers the section and refreshes the versions and the Job. */
export function useChangeRequestCommand(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (command: Command) => {
      if (command.action === 'propose') {
        return unwrap(
          apiClient.POST('/api/v1/boards/{boardId}/change-requests', {
            params: { path: { boardId: jobId } },
            body: command.body,
          }),
        )
      }
      const path = { params: { path: { changeRequestId: command.requestId } } }
      switch (command.action) {
        case 'impact':
          return unwrap(apiClient.POST('/api/v1/change-requests/{changeRequestId}/impact', { ...path, body: { impact: command.impact } }))
        case 'approve':
          return unwrap(
            apiClient.POST('/api/v1/change-requests/{changeRequestId}/approve', { ...path, body: { version: command.version } }),
          )
        case 'confirm':
          return unwrap(
            apiClient.POST('/api/v1/change-requests/{changeRequestId}/confirm', { ...path, body: { version: command.version } }),
          )
        default:
          return unwrap(apiClient.POST('/api/v1/change-requests/{changeRequestId}/withdraw', path))
      }
    },
    onSuccess: async (view) => {
      queryClient.setQueryData(['change-requests', jobId], view)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['agreement-versions', jobId] }),
        queryClient.invalidateQueries({ queryKey: NOTIFICATION_QUERY_KEY }),
      ])
    },
  })
}
