import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { busyKey } from '@/features/job/jobBusy'
import { NOTIFICATION_QUERY_KEY } from '@/features/notification/api'
import { apiClient, formBody, unwrap } from '@/lib/apiClient'

export type HandoverList = components['schemas']['HandoverListView']
export type HandoverPreflight = components['schemas']['HandoverPreflightView']
export type HandoverRequest = components['schemas']['HandoverRequest']
export type DeliveryReport = components['schemas']['DeliveryReportView']
export type HandoverScreenshot = components['schemas']['HandoverScreenshotView']
export type PreviewCheck = components['schemas']['PreviewCheckView']

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
 * it refreshes the report, the list, JOB-1, the to-dos, and the busy banner a Code Job's merge sets.
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
        queryClient.invalidateQueries({ queryKey: busyKey(jobId) }),
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

/** "Kiểm tra đường dẫn": whether the preview link responds now (FR-32, AD-32); "Gửi bàn giao" checks again. */
export function useCheckPreview(jobId: string) {
  return useMutation({
    mutationFn: (url: string) =>
      unwrap(apiClient.POST('/api/v1/boards/{boardId}/handovers/preview-check', { params: { path: { boardId: jobId } }, body: { url } })),
  })
}

/** A screenshot for the next handover; the preflight lists the ones waiting. */
export function useAddHandoverScreenshot(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (file: File) => {
      const form = new FormData()
      form.append('file', file)
      return unwrap(
        apiClient.POST('/api/v1/boards/{boardId}/handovers/screenshots', {
          params: { path: { boardId: jobId } },
          body: formBody(form),
        }),
      )
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['handovers', jobId, 'preflight'] }),
  })
}

/** Removes a waiting screenshot that no handover carries yet. */
export function useRemoveHandoverScreenshot(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (fileId: string) =>
      unwrap(
        apiClient.DELETE('/api/v1/boards/{boardId}/handovers/screenshots/{fileId}', {
          params: { path: { boardId: jobId, fileId } },
        }),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['handovers', jobId, 'preflight'] }),
  })
}
