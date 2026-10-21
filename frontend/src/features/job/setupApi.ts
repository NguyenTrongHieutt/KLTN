import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'

export type Setup = components['schemas']['SetupView']

/** How often the step tracker reads the Setup while the Job is Starting (UX-DR23). */
export const SETUP_POLL_MS = 3000

const setupKey = (jobId: string) => ['job', 'setup', jobId] as const

/** JOB-H's step tracker: the Setup's steps, polled every 3 s. */
export function useSetup(jobId: string | undefined) {
  return useQuery({
    queryKey: setupKey(jobId ?? ''),
    queryFn: () => unwrap(apiClient.GET('/api/v1/jobs/{jobId}/setup', { params: { path: { jobId: jobId ?? '' } } })),
    enabled: jobId !== undefined,
    refetchInterval: SETUP_POLL_MS,
    retry: false,
  })
}

/** "Thử lại" by the Customer after a block. */
export function useRetrySetup(jobId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => unwrap(apiClient.POST('/api/v1/jobs/{jobId}/setup/retry', { params: { path: { jobId } } })),
    onSuccess: (setup) => client.setQueryData(setupKey(jobId), setup),
  })
}
