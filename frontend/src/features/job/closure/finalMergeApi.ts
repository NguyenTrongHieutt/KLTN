import { useMutation, useQuery } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'

export type FinalMerge = components['schemas']['FinalMergeView']

/** The Customer's step "Cho phép hợp nhất" (FR-37, AD-10); others, and an Assessment Job, find nothing. */
export function useFinalMerge(jobId: string) {
  return useQuery({
    queryKey: ['final-merge', jobId],
    queryFn: () => unwrap(apiClient.GET('/api/v1/jobs/{jobId}/final-merge', { params: { path: { jobId } } })),
    retry: false,
  })
}

/** A fresh Merge App install link with its single-use state; the page then goes to GitHub. */
export function useInstallLink(jobId: string) {
  return useMutation({
    mutationFn: () =>
      unwrap(apiClient.POST('/api/v1/jobs/{jobId}/final-merge/install-link', { params: { path: { jobId } } })),
  })
}
