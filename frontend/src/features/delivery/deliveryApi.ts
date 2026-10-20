import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { NOTIFICATION_QUERY_KEY } from '@/features/notification/api'
import { apiClient, unwrap } from '@/lib/apiClient'

export type HandoverList = components['schemas']['HandoverListView']
export type HandoverPreflight = components['schemas']['HandoverPreflightView']
export type HandoverRequest = components['schemas']['HandoverRequest']
export type DeliveryReport = components['schemas']['DeliveryReportView']

/** JOB-5's list of the Job's handovers (FR-32); a Board's ID is its Job's. */
export function useHandovers(jobId: string | undefined) {
  return useQuery({
    queryKey: ['handovers', jobId ?? ''],
    queryFn: () =>
      unwrap(apiClient.GET('/api/v1/boards/{boardId}/handovers', { params: { path: { boardId: jobId ?? '' } } })),
    enabled: jobId !== undefined,
    retry: false,
  })
}

/** The Mentor's preflight for "Tạo bàn giao" (UX-DR54): the Milestone waiting for its handover. */
export function useHandoverPreflight(jobId: string | undefined) {
  return useQuery({
    queryKey: ['handovers', jobId ?? '', 'preflight'],
    queryFn: () =>
      unwrap(apiClient.GET('/api/v1/boards/{boardId}/handovers/preflight', { params: { path: { boardId: jobId ?? '' } } })),
    enabled: jobId !== undefined,
    retry: false,
  })
}

/** "Gửi bàn giao": M2 on the loaded Job version (AD-23); it refreshes the list, JOB-1, and the to-dos. */
export function useHandOver(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: HandoverRequest) =>
      unwrap(apiClient.POST('/api/v1/boards/{boardId}/handovers', { params: { path: { boardId: jobId } }, body })),
    onSuccess: async (report) => {
      queryClient.setQueryData(['handover', report.id], report)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['handovers', jobId] }),
        queryClient.invalidateQueries({ queryKey: ['progress', jobId] }),
        queryClient.invalidateQueries({ queryKey: NOTIFICATION_QUERY_KEY }),
      ])
    },
  })
}

export type HandoverDecisionRequest = components['schemas']['HandoverDecisionRequest']

/**
 * The Customer's decision (FR-33): "Nghiệm thu giai đoạn" or "Yêu cầu sửa", on the loaded Job version;
 * it refreshes the report, the list, JOB-1, and the to-dos.
 */
export function useDecideHandover(jobId: string, handoverId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ action, body }: { action: 'accept' | 'revision'; body: HandoverDecisionRequest }) =>
      action === 'accept'
        ? unwrap(apiClient.POST('/api/v1/handovers/{handoverId}/accept', { params: { path: { handoverId } }, body }))
        : unwrap(apiClient.POST('/api/v1/handovers/{handoverId}/revision', { params: { path: { handoverId } }, body })),
    onSuccess: async (report) => {
      queryClient.setQueryData(['handover', handoverId], report)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['handovers', jobId] }),
        queryClient.invalidateQueries({ queryKey: ['progress', jobId] }),
        queryClient.invalidateQueries({ queryKey: NOTIFICATION_QUERY_KEY }),
      ])
    },
  })
}

/** One Delivery Report (UX-DR53). */
export function useHandover(handoverId: string | undefined) {
  return useQuery({
    queryKey: ['handover', handoverId ?? ''],
    queryFn: () =>
      unwrap(apiClient.GET('/api/v1/handovers/{handoverId}', { params: { path: { handoverId: handoverId ?? '' } } })),
    enabled: handoverId !== undefined,
    retry: false,
  })
}
