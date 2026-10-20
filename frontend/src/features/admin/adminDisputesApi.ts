import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'
import { OVERVIEW_QUERY_KEY } from './adminApi'

export type AdminDisputeRow = components['schemas']['AdminDisputeRow']
export type AdminDispute = components['schemas']['AdminDisputeDetail']

const DISPUTES_QUERY_KEY = ['admin', 'disputes'] as const

/** ADM-4's list of Disputes (FR-54). */
export function useAdminDisputes() {
  return useQuery({
    queryKey: DISPUTES_QUERY_KEY,
    queryFn: () => unwrap(apiClient.GET('/api/v1/admin/disputes')),
    retry: false,
  })
}

/** One Dispute with its Held Milestone, decision, and execution. */
export function useAdminDispute(disputeId: string | undefined) {
  return useQuery({
    queryKey: [...DISPUTES_QUERY_KEY, disputeId ?? ''],
    queryFn: () =>
      unwrap(apiClient.GET('/api/v1/admin/disputes/{disputeId}', { params: { path: { disputeId: disputeId ?? '' } } })),
    enabled: disputeId !== undefined,
    retry: false,
  })
}

type Command =
  | { action: 'take' }
  | { action: 'retry' }
  | {
      action: 'decide'
      body: { releaseVnd?: number; refundVnd?: number; nextState: 'CONTINUE' | 'END' | 'CANCEL'; reasoning: string; stepUpCode: string }
    }

/** "Nhận xử lý", the decision after the step-up, or "Thử lại"; the list, the detail, and ADM-1 refresh. */
export function useDisputeCommand(disputeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (command: Command) => {
      const path = { params: { path: { disputeId } } }
      if (command.action === 'take') {
        return unwrap(apiClient.POST('/api/v1/admin/disputes/{disputeId}/take', path))
      }
      if (command.action === 'retry') {
        return unwrap(apiClient.POST('/api/v1/admin/disputes/{disputeId}/retry', path))
      }
      return unwrap(apiClient.POST('/api/v1/admin/disputes/{disputeId}/decision', { ...path, body: command.body }))
    },
    onSuccess: (detail) => queryClient.setQueryData([...DISPUTES_QUERY_KEY, disputeId], detail),
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: DISPUTES_QUERY_KEY }),
        queryClient.invalidateQueries({ queryKey: OVERVIEW_QUERY_KEY }),
      ]),
  })
}
