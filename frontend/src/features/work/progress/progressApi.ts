import { useQuery } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'

export type JobProgress = components['schemas']['JobProgressView']
export type MilestoneProgress = components['schemas']['MilestoneProgressView']

/** JOB-1's progress (FR-28), polled every 30 s while the overview is open (UX-DR25). */
export function useJobProgress(jobId: string | undefined) {
  return useQuery({
    queryKey: ['progress', jobId ?? ''],
    queryFn: () =>
      unwrap(apiClient.GET('/api/v1/boards/{boardId}/progress', { params: { path: { boardId: jobId ?? '' } } })),
    enabled: jobId !== undefined,
    refetchInterval: 30_000,
    retry: false,
  })
}
