import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'

export type MentorJob = components['schemas']['MentorJobView']
export type MentorJobSummary = components['schemas']['MentorJobSummaryView']

export const MENTOR_JOB_KEY = ['mentor-job'] as const

const KEY = MENTOR_JOB_KEY

/** MEN-4 "Công việc của bạn": the Jobs whose Mentor the account is (AD-30). */
export function useMentorJobs() {
  return useQuery({ queryKey: [...KEY, 'list'], queryFn: () => unwrap(apiClient.GET('/api/v1/mentor/jobs')) })
}

/** The Mentor's JOB-1. */
export function useMentorJob(jobId: string | undefined, enabled = true) {
  return useQuery({
    queryKey: [...KEY, 'detail', jobId],
    queryFn: () => unwrap(apiClient.GET('/api/v1/mentor/jobs/{jobId}', { params: { path: { jobId: jobId ?? '' } } })),
    enabled: enabled && jobId !== undefined,
    retry: false,
  })
}

/** The NDA panel's "Ký cam kết" (FR-15): the signature and J4. */
export function useSignNda(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: { ndaVersion: string; commitEmailNoticeAcknowledged: boolean }) =>
      unwrap(apiClient.POST('/api/v1/mentor/jobs/{jobId}/nda', { params: { path: { jobId } }, body })),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: KEY }),
  })
}

/** The NDA panel's "Từ chối" (FR-15): J5 at the version the screen loaded. */
export function useDeclineSelection(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (version: number) =>
      unwrap(apiClient.POST('/api/v1/mentor/jobs/{jobId}/decline', { params: { path: { jobId } }, body: { version } })),
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: KEY }),
        queryClient.invalidateQueries({ queryKey: ['application'] }),
      ]),
  })
}

/** JOB-1 "Hủy lựa chọn" (FR-15): J5 once the signature period has passed. */
export function useCancelSelection(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (version: number) =>
      unwrap(apiClient.POST('/api/v1/jobs/{jobId}/selection/cancel', { params: { path: { jobId } }, body: { version } })),
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: ['job'] }),
        queryClient.invalidateQueries({ queryKey: ['application'] }),
      ]),
  })
}
