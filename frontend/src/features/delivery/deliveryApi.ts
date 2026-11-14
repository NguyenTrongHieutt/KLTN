import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { busyKey } from '@/features/job/jobBusy'
import { NOTIFICATION_QUERY_KEY } from '@/features/notification/api'
import vi from '@/i18n/vi.json'
import { apiClient, formBody, unwrap } from '@/lib/apiClient'

export type HandoverList = components['schemas']['HandoverListView']
export type HandoverPreflight = components['schemas']['HandoverPreflightView']
export type HandoverRequest = components['schemas']['HandoverRequest']
export type DeliveryReport = components['schemas']['DeliveryReportView']
export type HandoverScreenshot = components['schemas']['HandoverScreenshotView']
export type PreviewCheck = components['schemas']['PreviewCheckView']
export type HandoverSubmission = components['schemas']['HandoverSubmissionView']

/** How often the form reads the submission again while the runner copies the handover (Story 23.8a). */
const SUBMISSION_POLL_MS = 3_000

/**
 * N-33's version, which a Code Job's handover names: the server's `vibefix.delivery.team-code-notice-version`,
 * mirrored in `vi.json` as N-32's and the product notices are (FR-25, R28, Story 23.8b).
 */
export const TEAM_CODE_NOTICE_VERSION = (vi as { notice: { teamCode: { version: string } } }).notice.teamCode.version

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

/**
 * "Gửi bàn giao" on the loaded Job version (AD-23). It answers 202 with the submission: an Assessment Job's
 * is done at once, and a Code Job's waits while the runner copies the Handover Commit (Story 23.8a). It
 * refreshes the list, JOB-1, the to-dos, and the busy marker the copy sets.
 */
export function useHandOver(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: HandoverRequest) =>
      unwrap(apiClient.POST('/api/v1/boards/{boardId}/handovers', { params: { path: { boardId: jobId } }, body })),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['handovers', jobId] }),
        queryClient.invalidateQueries({ queryKey: ['progress', jobId] }),
        queryClient.invalidateQueries({ queryKey: busyKey(jobId) }),
        queryClient.invalidateQueries({ queryKey: NOTIFICATION_QUERY_KEY }),
      ])
    },
  })
}

/**
 * The Job's latest handover submission, read again every 3 s while the runner copies it (Story 23.8a); the form
 * that sent it waits here for the handover, or for the reason the copy was refused.
 */
export function useHandoverSubmission(jobId: string, enabled: boolean) {
  return useQuery({
    queryKey: ['handovers', jobId, 'submission'],
    queryFn: () =>
      unwrap(apiClient.GET('/api/v1/boards/{boardId}/handovers/submission', { params: { path: { boardId: jobId } } })),
    enabled,
    retry: 2,
    // While the read has no answer yet, or its answer is pending: a failed read is shown and tried again.
    refetchInterval: (query) =>
      query.state.data === undefined || query.state.data.state === 'PENDING' ? SUBMISSION_POLL_MS : false,
  })
}

/**
 * JOB-5's copy row for the Mentor and the Crew (UX-DR115, Story 23.8b): the Job's latest submission, read once
 * when the screen opens and then every 3 s while its copy is still pending, as every run status row polls. The
 * Customer is refused this read (`TEAM_ONLY`), and a Job that never sent one answers 404, which shows nothing.
 */
export function useCopyingHandover(jobId: string | undefined) {
  return useQuery({
    queryKey: ['handovers', jobId ?? '', 'submission'],
    queryFn: () =>
      unwrap(apiClient.GET('/api/v1/boards/{boardId}/handovers/submission', { params: { path: { boardId: jobId ?? '' } } })),
    enabled: jobId !== undefined,
    retry: false,
    refetchInterval: (query) => (query.state.data?.state === 'PENDING' ? SUBMISSION_POLL_MS : false),
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
