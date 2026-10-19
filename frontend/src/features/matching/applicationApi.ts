import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'

export type Application = components['schemas']['ApplicationView']
export type JobApplication = components['schemas']['JobApplicationView']
export type ApplyRequest = components['schemas']['ApplyRequest']
export type ApplicationStatus = Application['status']

const KEY = ['application'] as const

/** MEN-4 and MEN-3: the Mentor's own Applications, newest first. */
export function useMyApplications(enabled = true) {
  return useQuery({
    queryKey: [...KEY, 'mine'],
    queryFn: () => unwrap(apiClient.GET('/api/v1/mentor/applications')),
    enabled,
  })
}

/** CUS-8: the open Applications to the Customer's Job. */
export function useJobApplications(jobId: string | undefined) {
  return useQuery({
    queryKey: [...KEY, 'job', jobId],
    queryFn: () => unwrap(apiClient.GET('/api/v1/jobs/{jobId}/applications', { params: { path: { jobId: jobId ?? '' } } })),
    enabled: jobId !== undefined,
    retry: false,
  })
}

/** MEN-3 "Ứng tuyển" (FR-13). */
export function useApply(postId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: ApplyRequest) =>
      unwrap(apiClient.POST('/api/v1/mentor/job-posts/{postId}/applications', { params: { path: { postId } }, body })),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: KEY }),
  })
}

/** "Rút đơn" before selection (FR-13). */
export function useWithdrawApplication() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (applicationId: string) =>
      unwrap(
        apiClient.POST('/api/v1/mentor/applications/{applicationId}/withdraw', {
          params: { path: { applicationId } },
        }),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: KEY }),
  })
}

/** Whether an Application still counts: submitted, on hold, or selected. */
export function isLive(application: { status: ApplicationStatus }): boolean {
  return application.status === 'SUBMITTED' || application.status === 'ON_HOLD' || application.status === 'SELECTED'
}
